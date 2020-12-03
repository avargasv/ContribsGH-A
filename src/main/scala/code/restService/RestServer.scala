package code.restService

import net.liftweb.http.rest.RestHelper
import net.liftweb.http._
import net.liftweb.json.JsonDSL._

import code.lib.AppAux._
import code.model.Entities._
import code.restService.RestClient.{contributorsByRepo, reposByOrganization}

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success, Try}

object RestServer extends RestHelper {

  serve({
    case Req("org" :: organization :: "contributors" :: Nil, _, GetRequest) =>
      val groupLevel = S.param("group-level").openOr("organization")
      val minContribs_S = S.param("min-contribs").openOr("NA")
      val minContribs = Try(minContribs_S.toInt) match {
        case Success(i) => i
        case Failure(_) => 0
      }
      logger.info(s"groupLevel='$groupLevel', minContribs=$minContribs")
      listContributors(organization, groupLevel, minContribs)
  })

  def listContributors(organization: String, groupLevel: String, minContribs: Int): LiftResponse = {
    val response: List[Contributor] = contributorsByOrganization(organization, groupLevel, minContribs)
    JsonResponse (response.map(_.asJson))
  }

  def contributorsByOrganization(organization: String, groupLevel: String, minContribs: Int): List[Contributor] = {
    val sdf = new java.text.SimpleDateFormat("dd-MM-yyyy hh:mm:ss")
    logger.info(s"Starting ContribsGH-P REST API call at ${sdf.format(new java.util.Date())} - organization='$organization'")

    val repos = reposByOrganization(organization)

    // parallel retrieval of contributors by repo
    // TODO using akka
    val contributorsDetailed_L_F: List[Future[List[Contributor]]] = repos.map { repo =>
      Future { contributorsByRepo(organization, repo) }
    }
    val contributorsDetailed_F_L: Future[List[List[Contributor]]] = Future.sequence(contributorsDetailed_L_F)
    val contributorsDetailed: List[Contributor] = Await.result(contributorsDetailed_F_L, timeout).flatten

    logger.info(s"Finished ContribsGH-P REST API call at ${sdf.format(new java.util.Date())} - organization='$organization'")

    // grouping, sorting
    val (contributorsGroupedAboveMin, contributorsGroupedBelowMin) = contributorsDetailed.
      map(c => if (groupLevel == "repo") c else c.copy(repo=s"All $organization repos")).
      groupBy(c => (c.repo, c.name)).
      mapValues(_.foldLeft(0)((acc, elt) => acc + elt.contributions)).
      map(p => Contributor(p._1._1, p._1._2, p._2)).
      partition(_.contributions >= minContribs)
    (
      contributorsGroupedAboveMin
      ++
      contributorsGroupedBelowMin.
        map(c => c.copy(name = "Other contributors")).
        groupBy(c => (c.repo, c.name)).
        mapValues(_.foldLeft(0)((acc, elt) => acc + elt.contributions)).
        map(p => Contributor(p._1._1, p._1._2, p._2))
    ).toList.sortWith { (c1: Contributor, c2: Contributor) =>
      if (c1.repo != c2.repo) c1.repo < c2.repo
      else if (c1.name == "Other contributors") false
      else if (c1.contributions != c2.contributions) c1.contributions >= c2.contributions
      else c1.name < c2.name
    }
  }

}
