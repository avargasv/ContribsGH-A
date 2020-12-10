package code.restService

import code.model.Entities._
import code.restService.RestClient.{contributorsByRepo, reposByOrganization}

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior, DispatcherSelector, Terminated }
import akka.util.Timeout
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
            val contribsGHOrg = context.spawn(ContribsGHOrg(org), org)
            context.self ! message
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
  // responde a un unico mensaje que devuelve List[Contributor] para org
  // usa el pattern 'per session child actor' para juntar las contribuciones de todos los repos de org
  // que se obtienen de actores ContribsGHRepo

  sealed trait ContributorsByRepo
  final case class ReqContributorsByRepo(repo: Repository, replyTo: ActorRef[ContributorsByRepo]) extends ContributorsByRepo
  final case class RespContributorsByRepo(resp: List[Contributor], replyTo: ActorRef[ContributorsByRepo]) extends ContributorsByRepo

  def apply(org: Organization): Behavior[ContribsGHMain.ContributorsByOrg] =
    repositories(org, Map.empty[Repository, ActorRef[ContributorsByRepo]])

  def repositories(org: Organization, repos: Map[Repository, ActorRef[ContributorsByRepo]]): Behavior[ContribsGHMain.ContributorsByOrg] =
    Behaviors.receive { (context, message) =>
      message match {
        case ContribsGHMain.ReqContributorsByOrg(org, replyTo) =>
          println(s"mensaje ReqContributorsByOrg recibido por ContribsGHOrg para org=$org con repos.size=${repos.size}")
          // TODO acumular el resultado devuelto por los elementos de repos y devolverlo
          replyTo ! ContribsGHMain.RespContributorsByOrg(List(Contributor(s"$org-repo1", "abc", 100), Contributor(s"$org-repo2", "xyz", 200)), null)
          Behaviors.same
      }
    }

}

class ContribsGHRepo(repo: Repository) {
  // repository actor
  // mantiene lista de contribuciones
  // crea la lista si el repo es nuevo
  // reemplaza la lista si el repo es obsoleto según updatedAt
  // responde a un unico mensaje que devuelve List[Contributor] para repo

}
