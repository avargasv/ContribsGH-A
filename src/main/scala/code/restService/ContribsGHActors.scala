package code.restService

import code.lib.AppAux._
import code.model.Entities._
import code.restService.RestClient.{contributorsByRepo, reposByOrganization}

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior, DispatcherSelector, Terminated }

object ContribsGHMain {
  // actor principal
  // mantiene mapa de organizaciones y referencias a sus actores
  // responde a un único mensaje que devuelve List[Contributor] para una organización
  // usa el pattern 'ask' para pedir las contribuciones a un actor ContribsGHOrg

  sealed trait ContributorsByOrg
  final case class ReqContributorsByOrg(org: Organization, replyTo: ActorRef[ContributorsByOrg]) extends ContributorsByOrg
  final case class RespContributorsByOrg(resp: List[Contributor]) extends ContributorsByOrg

  def apply(): Behavior[ContributorsByOrg] =
    organizations(Map.empty[Organization, ActorRef[ContributorsByOrg]])

  def organizations(orgs: Map[Organization, ActorRef[ContributorsByOrg]]): Behavior[ContributorsByOrg] =
    Behaviors.receive { (context, message) =>
      message match {
        case msg @ ReqContributorsByOrg(org, replyTo) =>
          println(s"mensaje ReqContributorsByOrg recibido para org=$org con orgs.size=${orgs.size}")
          if (orgs.contains(org)) {
            // TODO devolver el resultado devuelto por orgs(org)
            replyTo ! RespContributorsByOrg(List(Contributor("repo1", "abc", 100), Contributor("repo2", "xyz", 200)))
            Behaviors.same
          } else {
            val contribsGHOrg = context.spawn(ContribsGHOrg(org), org)
            context.self ! msg
            organizations(orgs + (org -> contribsGHOrg))
          }
      }
    }

}

object ContribsGHOrg {
  // organization actor
  // mantiene mapa de repositorios y referencias a sus actores
  // responde a un unico mensaje que devuelve List[Contributor] para org
  // usa el pattern 'per session child actor' para juntar las contribuciones de todos los repos de org
  // que se obtienen de actores ContribsGHRepo
  import ContribsGHMain._
  final case class ContributorsByRepo(repo: Repository)

  def apply(org: Organization): Behavior[ContributorsByOrg] =
    Behaviors.receive { (context, message) =>
        Behaviors.same
    }


}

class ContribsGHRepo(repo: Repository) {
  // repository actor
  // mantiene lista de contribuciones
  // crea la lista si el repo es nuevo
  // reemplaza la lista si el repo es obsoleto según updatedAt
  // responde a un unico mensaje que devuelve List[Contributor] para repo
}
