package code.restService

import java.time.Instant

import code.model.Entities._
import code.restService.RestClient.{contributorsByRepo, reposByOrganization}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.util.Timeout
import code.restService.ContribsGHMain.{ContributorsByOrg, RespContributorsByOrg}

import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success, Try}

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

  def organizations(orgs: Map[Organization, ActorRef[ContributorsByOrg]]): Behavior[ContributorsByOrg] =
    Behaviors.receive { (context, message) =>
      message match {
        case ReqContributorsByOrg(org, replyTo) =>
          println(s"mensaje ReqContributorsByOrg recibido por ContribsGHMain para org=$org con orgs.size=${orgs.size}")
          if (orgs.contains(org)) {
            implicit val timeout: Timeout = 30.seconds
            context.ask(orgs(org))((ref: ActorRef[ContributorsByOrg]) => ReqContributorsByOrg(org, ref)) {
              case Success(resp: RespContributorsByOrg) => resp.copy(originalSender=replyTo)
              case Failure(_) => RespContributorsByOrg(List.empty[Contributor], replyTo)
            }
            Behaviors.same
          } else {
            context.self ! message
            val contribsGHOrg = context.spawn(ContribsGHOrg(org), org)
            organizations(orgs + (org -> contribsGHOrg))
          }
        case RespContributorsByOrg(org, originalSender) =>
          println(s"mensaje RespContributorsByOrg recibido por ContribsGHMain")
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
                   repos: Map[Repository, ActorRef[ContributionsByRepo]],
                   reposRemaining: List[Tuple2[Repository, ActorRef[ContributionsByRepo]]],
                   contributorsSoFar: List[Contributor]): Behavior[ContribsGHMain.ContributorsByOrg] =
    Behaviors.receive { (context, message) =>
      message match {
        case ContribsGHMain.ReqContributorsByOrg(org, replyTo) =>
          println(s"mensaje ReqContributorsByOrg recibido por ContribsGHOrg para org=$org con repos.size=${repos.size}")
          // TODO llamar a reposByOrganization
          val repos_L = List(Repository(s"$org-repo1", Instant.now), Repository(s"$org-repo2", Instant.now))
          val newRepos = repos_L.filter(r => !repos.keys.map(_.name).toSet.contains(r.name))
          if (newRepos.length > 0) {
            context.self ! message
            val reposUpdated = repos ++ newRepos.map( repo => repo -> context.spawn(ContribsGHRepo(repo), repo.name) )
            repositories(org, replyTo, reposUpdated, reposUpdated.toList, contributorsSoFar)
          } else {
            for (repo <- repos.keys) repos(repo) ! ReqContributionsByRepo(repo, context.self)
            repositories(org, replyTo, repos, repos.toList, contributorsSoFar)
          }
        case ContribsGHMain.RespContributionsByRepo(repo, resp) =>
          println(s"mensaje RespContributorsByRepo recibido por ContribsGHOrg con reposRemaining.length=${reposRemaining.length}")
          // acumula el resultado devuelto por los repositorios de la organización y si está completo lo devuelve
          val newContributors = resp.map(c => Contributor(repo.name, c.contributor, c.contributions))
          if (reposRemaining.length == 1 && reposRemaining.head._1.name == repo.name) {
            println(s"mensaje RespContributorsByOrg enviado a ${originalSender.path}")
            originalSender ! RespContributorsByOrg(contributorsSoFar ++ newContributors, originalSender)
            repositories(org, originalSender, repos,
              List.empty[Tuple2[Repository, ActorRef[ContributionsByRepo]]], List.empty[Contributor])
          } else {
            repositories(org, originalSender, repos,
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

  def apply(repo: Repository): Behavior[ContribsGHOrg.ContributionsByRepo] =
    contributions(repo, List.empty[Contributions])

  def contributions(repo: Repository, contribs: List[Contributions]) : Behavior[ContribsGHOrg.ContributionsByRepo]= {
    Behaviors.receive { (context, message) =>
      message match {
        case ContribsGHOrg.ReqContributionsByRepo(repo, replyTo) =>
          println(s"mensaje ReqContributorsByRepo recibido por ContribsGHRepo para repo=${repo.name} con contribs.size=${contribs.size}")
          // TODO agregar condorganizacióición de repo recién actualizado
          if (contribs.size == 0) {
            context.self ! message
            // TODO llamar a contributorsByRepo
            val contribs_L = List(Contributions("abc", 100), Contributions("xyz", 200))
            contributions(repo, contribs_L)
          } else {
            println(s"mensaje RespContributorsByRepo enviado a ${replyTo.path}")
            replyTo ! ContribsGHMain.RespContributionsByRepo(repo, contribs)
            Behaviors.same
          }
      }
    }
  }

}
