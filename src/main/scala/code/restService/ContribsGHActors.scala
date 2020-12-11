package code.restService

import code.model.Entities._
import code.restService.RestClient.{contributorsByRepo, reposByOrganization}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.util.Timeout
import code.restService.ContribsGHMain.{ContributorsByOrg, RespContributorsByOrg}

import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

object ContribsGHMain {
  // actor principal
  // mantiene mapa de organizaciones y referencias a sus actores
  // responde a un único mensaje que devuelve List[Contributor] para una organización
  // usa el pattern 'ask' para pedir las contribuciones a un actor ContribsGHOrg

  sealed trait ContributorsByOrg
  final case class ReqContributorsByOrg(org: Organization, replyTo: ActorRef[ContributorsByOrg]) extends ContributorsByOrg
  final case class RespContributorsByOrg(resp: List[Contributor], originalSender: ActorRef[ContributorsByOrg]) extends ContributorsByOrg
  final case class RespContributionsByRepo(repo: Repository, resp: List[Contributions]) extends ContributorsByOrg

  def apply(): Behavior[ContributorsByOrg] =
    organizations(Map.empty[Organization, ActorRef[ContributorsByOrg]])

  def organizations(orgs_M: Map[Organization, ActorRef[ContributorsByOrg]]): Behavior[ContributorsByOrg] =
    Behaviors.receive { (context, message) =>
      message match {
        case ReqContributorsByOrg(org, replyTo) =>
          //println(s"mensaje ReqContributorsByOrg recibido por ContribsGHMain para org=$org con orgs.size=${orgs.size}")
          if (orgs_M.contains(org)) {
            implicit val timeout: Timeout = 30.seconds
            context.ask(orgs_M(org))((ref: ActorRef[ContributorsByOrg]) => ReqContributorsByOrg(org, ref)) {
              case Success(resp: RespContributorsByOrg) => resp.copy(originalSender=replyTo)
              case Failure(_) => RespContributorsByOrg(List.empty[Contributor], replyTo)
            }
            Behaviors.same
          } else {
            context.self ! message
            val contribsGHOrg = context.spawn(ContribsGHOrg(org), org)
            organizations(orgs_M + (org -> contribsGHOrg))
          }
        case RespContributorsByOrg(org, originalSender) =>
          //println(s"mensaje RespContributorsByOrg recibido por ContribsGHMain")
          originalSender ! message
          Behaviors.same
      }
    }

}

object ContribsGHOrg {
  // organization actor
  // mantiene mapa de repositorios y referencias a sus actores
  // responde a un único mensaje que devuelve List[Contributor] para org
  // acumula y luego devuelve las contribuciones de todos los repos de org

  sealed trait ContributionsByRepo
  final case class ReqContributionsByRepo(repo: Repository, replyTo: ActorRef[ContributorsByOrg]) extends ContributionsByRepo

  def apply(org: Organization): Behavior[ContribsGHMain.ContributorsByOrg] =
    repositories(org, null, Map.empty[Repository, ActorRef[ContributionsByRepo]],
      List.empty[Tuple2[Repository, ActorRef[ContributionsByRepo]]], List.empty[Contributor])

  def repositories(org: Organization, originalSender: ActorRef[ContributorsByOrg],
                   repos_M: Map[Repository, ActorRef[ContributionsByRepo]],
                   reposRemaining: List[Tuple2[Repository, ActorRef[ContributionsByRepo]]],
                   contributorsSoFar: List[Contributor]): Behavior[ContribsGHMain.ContributorsByOrg] =
    Behaviors.receive { (context, message) =>
      message match {
        case ContribsGHMain.ReqContributorsByOrg(org, replyTo) =>
          //println(s"mensaje ReqContributorsByOrg recibido por ContribsGHOrg para org=$org con repos.size=${repos.size}")
          val repos_L = reposByOrganization(org)
          if (repos_L.length == 0) {
            replyTo ! RespContributorsByOrg(List.empty[Contributor], originalSender)
            Behaviors.same
          } else {
            val newRepos = repos_L.filter(r => !repos_M.keys.map(_.name).toSet.contains(r.name))
            val updatedRepos = repos_L.filter(r =>
              repos_M.keys.map(_.name).toSet.contains(r.name) &&
                repos_M.keys.find(_.name == r.name).get.updatedAt.compareTo(r.updatedAt) < 0).toSet
            // detiene los actores de los repos modificados desde la última vez que se actualizó repos_M
            repos_M.keys.foreach(k => if (updatedRepos.contains(k)) context.stop(repos_M(k)))
            if (newRepos.length > 0 || updatedRepos.size > 0) {
              context.self ! message
              val repos_M_updated = (repos_M -- updatedRepos) ++
                (newRepos ++ updatedRepos).map(repo => repo -> context.spawn(ContribsGHRepo(org, repo), repo.name))
              repositories(org, replyTo, repos_M_updated, repos_M_updated.toList, contributorsSoFar)
            } else {
              for (repo <- repos_M.keys) repos_M(repo) ! ReqContributionsByRepo(repo, context.self)
              repositories(org, replyTo, repos_M, repos_M.toList, contributorsSoFar)
            }
          }
        case ContribsGHMain.RespContributionsByRepo(repo, resp) =>
          //println(s"mensaje RespContributorsByRepo recibido por ContribsGHOrg con reposRemaining.length=${reposRemaining.length}")
          // acumula el resultado devuelto por los repositorios de la organización y si está completo lo devuelve
          val newContributors = resp.map(c => Contributor(repo.name, c.contributor, c.contributions))
          if (reposRemaining.length == 1 && reposRemaining.head._1.name == repo.name) {
            //println(s"mensaje RespContributorsByOrg enviado a ${originalSender.path}")
            originalSender ! RespContributorsByOrg(contributorsSoFar ++ newContributors, originalSender)
            repositories(org, originalSender, repos_M,
              List.empty[Tuple2[Repository, ActorRef[ContributionsByRepo]]], List.empty[Contributor])
          } else {
            repositories(org, originalSender, repos_M,
              reposRemaining.filter(_._1.name != repo.name), contributorsSoFar ++ newContributors)
          }

      }
    }

}

object ContribsGHRepo {
  // repository actor
  // mantiene lista de contribuciones
  // crea la lista si el repo es nuevo
  // reemplaza la lista si el repo es obsoleto según updatedAt
  // responde a un único mensaje que devuelve List[Contributor] para repo

  def apply(org: Organization, repo: Repository): Behavior[ContribsGHOrg.ContributionsByRepo] =
    contributions(org, repo, List.empty[Contributions])

  def contributions(org: Organization, repo: Repository, contribs: List[Contributions]) : Behavior[ContribsGHOrg.ContributionsByRepo]= {
    Behaviors.receive { (context, message) =>
      message match {
        case ContribsGHOrg.ReqContributionsByRepo(repoUpdated, replyTo) =>
          //println(s"mensaje ReqContributorsByRepo recibido por ContribsGHRepo para repo=${repo.name} con contribs.size=${contribs.size}")
          if (contribs.length == 0) {
            val contribs_L = contributorsByRepo(org, repo).map(c => Contributions(c.contributor, c.contributions))
            if (contribs_L.length == 0)
              replyTo ! ContribsGHMain.RespContributionsByRepo(repo, contribs)
            else
              context.self ! message
            contributions(org, repo, contribs_L)
          } else {
            //println(s"mensaje RespContributorsByRepo enviado a ${replyTo.path}")
            replyTo ! ContribsGHMain.RespContributionsByRepo(repo, contribs)
            Behaviors.same
          }
      }
    }
  }

}
