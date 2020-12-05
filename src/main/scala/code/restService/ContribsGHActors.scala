package code.restService

import akka.NotUsed
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior, DispatcherSelector, Terminated }

object ContribsGHMain {
  // main actor
  // mantiene mapa de organizaciones y referencias a sus actores
}

class ContribsGHOrg {
  // organization actor
  // mantiene mapa de repositorios y referencias a sus actores
}

class ContribsGHRepo {
  // repository actor
  // mantiene lista de contribuciones
  // crea la lista si el repo es nuevo
  // reemplaza la lista si el repo es obsoleto según updatedAt
}
