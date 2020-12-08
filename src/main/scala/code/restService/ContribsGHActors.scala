package code.restService

import code.lib.AppAux._
import code.model.Entities._
import code.restService.RestClient.{contributorsByRepo, reposByOrganization}

import akka.NotUsed
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior, DispatcherSelector, Terminated }

object ContribsGHMain {
  // main actor
  // mantiene mapa de organizaciones y referencias a sus actores
  // responde a un unico mensaje que devuelve List[Contributor] para una organización
  // usa el pattern 'ask' para pedir las contribuciones a un actor ContribsGHOrg
}

class ContribsGHOrg(org: Organization) {
  // organization actor
  // mantiene mapa de repositorios y referencias a sus actores
  // responde a un unico mensaje que devuelve List[Contributor] para org
  // usa el pattern 'per session child actor' para juntar las contribuciones de todos los repos de org
  // que se obtienen de actores ContribsGHRepo
}

class ContribsGHRepo(repo: Repository) {
  // repository actor
  // mantiene lista de contribuciones
  // crea la lista si el repo es nuevo
  // reemplaza la lista si el repo es obsoleto según updatedAt
  // responde a un unico mensaje que devuelve List[Contributor] para repo
}
