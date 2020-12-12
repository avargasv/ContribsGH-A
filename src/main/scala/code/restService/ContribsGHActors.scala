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
  // mantiene un mapa de organizaciones y referencias a sus actores
  // responde a un único mensaje que devuelve List[Contributor] para una organización
  // usa el pattern 'ask' para pedir List[Contributor] a un actor ContribsGHOrg

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
          if (orgs_M.contains(org)) {
            implicit val timeout: Timeout = 15.seconds
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
          originalSender ! message
          Behaviors.same
      }
    }

}

object ContribsGHOrg {
  // organization actor
  // mantiene un mapa de repositorios y referencias a sus actores
  // acumula y devuelve las contribuciones de los repositorios,
  // las cuales  se piden a un actor ContribsGHRepo por cada repositorio

  sealed trait ContributionsByRepo
  final case class ReqContributionsByRepo(replyTo: ActorRef[ContributorsByOrg]) extends ContributionsByRepo

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
          val repos_L = reposByOrganization(org)
          if (repos_L.length == 0) {
            // recupera del cache, si existe
            if (repos_M.size > 0) {
              for (repo <- repos_M.keys) repos_M(repo) ! ReqContributionsByRepo(context.self)
              repositories(org, replyTo, repos_M, repos_M.toList, List.empty[Contributor])
            } else {
              replyTo ! RespContributorsByOrg(List.empty[Contributor], originalSender)
              Behaviors.same
            }
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
              for (repo <- repos_M.keys) repos_M(repo) ! ReqContributionsByRepo(context.self)
              repositories(org, replyTo, repos_M, repos_M.toList, contributorsSoFar)
            }
          }
        case ContribsGHMain.RespContributionsByRepo(repo, resp) =>
          // acumula el resultado devuelto por los repositorios de la organización y si está completo lo devuelve
          val newContributors = resp.map(c => Contributor(repo.name, c.contributor, c.contributions))
          if (reposRemaining.length == 1 && reposRemaining.head._1.name == repo.name) {
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
  // mantiene una lista de contribuciones para el repo
  // carga la lista usando el cliente REST si ésta no ha sido cargada anteriormente

  def apply(org: Organization, repo: Repository): Behavior[ContribsGHOrg.ContributionsByRepo] =
    contributions(org, repo, List.empty[Contributions])

  def contributions(org: Organization, repo: Repository, contribs: List[Contributions]) : Behavior[ContribsGHOrg.ContributionsByRepo]= {
    Behaviors.receive { (context, message) =>
      message match {
        case ContribsGHOrg.ReqContributionsByRepo(replyTo) =>
          if (contribs.length == 0) {
            val contribs_L = contributorsByRepo(org, repo).map(c => Contributions(c.contributor, c.contributions))
            if (contribs_L.length == 0)
              replyTo ! ContribsGHMain.RespContributionsByRepo(repo, contribs_L)
            else
              context.self ! message
            contributions(org, repo, contribs_L)
          } else {
            replyTo ! ContribsGHMain.RespContributionsByRepo(repo, contribs)
            Behaviors.same
          }
      }
    }
  }

}
