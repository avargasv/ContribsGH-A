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
  // main actor
  // holds a map of organizations and references to their corresponding actors
  // responds to a single message requesting the List[Contributor] for an organization
  // responds using the 'ask' pattern to request a List[Contributor] to a ContribsGHOrg actor

  trait ContributorsByOrg
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
        case RespContributorsByOrg(resp, originalSender) =>
          originalSender ! message
          Behaviors.same
      }
    }

}

object ContribsGHOrg {
  // organization actor
  // holds a map of the repositories of an organizations and references to their corresponding actors
  // responds to a single message requesting the List[Contributor] for an organization
  // to respond:
  // - accumulates the contributions of the repositories, requested to one ContribsGHRepo actor for each repository
  // - returns the accumulated contributions

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
            // retrieves from the cache, if it exists
            if (repos_M.size > 0) {
              for (repo <- repos_M.keys) repos_M(repo) ! ReqContributionsByRepo(context.self)
              repositories(org, replyTo, repos_M, repos_M.toList, List.empty[Contributor])
            } else {
              replyTo ! RespContributorsByOrg(List.empty[Contributor], originalSender)
              Behaviors.same
            }
          } else {
            // detects new repos and repos modified after their update date registered in repos_M
            val newRepos = repos_L.filter(r => !repos_M.keys.map(_.name).toSet.contains(r.name))
            val modifiedRepos = repos_L.filter(r =>
              repos_M.keys.map(_.name).toSet.contains(r.name) &&
                repos_M.keys.find(_.name == r.name).get.updatedAt.compareTo(r.updatedAt) < 0).toSet
            // stops the actors of the repos modified since the last time repos_M was updated
            repos_M.keys.foreach(k => if (modifiedRepos.contains(k)) context.stop(repos_M(k)))
            // send messages to the actors of the map repos_M to accumulate their responses
            if (newRepos.length > 0 || modifiedRepos.size > 0) {
              // using the new repos map that includes new and modified repos
              context.self ! message
              val repos_M_updated = (repos_M -- modifiedRepos) ++
                (newRepos ++ modifiedRepos).map(repo => repo -> context.spawn(ContribsGHRepo(org, repo), repo.name))
              repositories(org, replyTo, repos_M_updated, repos_M_updated.toList, List.empty[Contributor])
            } else {
              // using the old repos map
              for (repo <- repos_M.keys) repos_M(repo) ! ReqContributionsByRepo(context.self)
              repositories(org, replyTo, repos_M, repos_M.toList, List.empty[Contributor])
            }
          }
        case ContribsGHMain.RespContributionsByRepo(repo, resp) =>
          // accumulates the responses of the repositories of the organization
          val newContributors = resp.map(c => Contributor(repo.name, c.contributor, c.contributions))
          if (reposRemaining.length == 1 && reposRemaining.head._1.name == repo.name) {
            // returns the performed accumulation after receiving the response of the last repository
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
  // holds a list of the contributions to a repository
  // responds to a single message requesting the List[Contributor] for the repository
  // to respond:
  // - builds the list using the REST client the first time the message is received
  // - afterwards, returns the previously built list without using the REST client again (cache)

  def apply(org: Organization, repo: Repository): Behavior[ContribsGHOrg.ContributionsByRepo] =
    contributions(org, repo, List.empty[Contributions])

  def contributions(org: Organization, repo: Repository, contribs: List[Contributions]) : Behavior[ContribsGHOrg.ContributionsByRepo] =
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
