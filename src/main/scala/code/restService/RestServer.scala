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
    val contributorsDetailed_L_F: List[Future[List[Contributor]]] = repos.map { repo =>
      Future { contributorsByRepo(organization, repo) }
    }
    val contributorsDetailed_F_L: Future[List[List[Contributor]]] = Future.sequence(contributorsDetailed_L_F)
    val contributorsDetailed_L: List[List[Contributor]] = Await.result(contributorsDetailed_F_L, timeout)
    val contributorsDetailed: List[Contributor] = contributorsDetailed_L.foldLeft(List.empty[Contributor])((acc, elt) => acc ++ elt)

    // grouping
    def contributorByGroupLevel(c: Contributor): Contributor = if (groupLevel == "repo") c else c.copy(repo="ALL")
    val contributorsGrouped = contributorsDetailed.
      map(c => contributorByGroupLevel(c)).
      groupBy(c => (c.repo, c.name)).
      mapValues(_.foldLeft(0)((acc, elt) => acc + elt.contributions)).toList.
      map(p => Contributor(p._1._1, p._1._2, p._2)).
      filter(_.contributions >= minContribs).
      sortBy(c => (c.repo, - c.contributions, c.name))
    logger.info(s"Finished ContribsGH-P REST API call at ${sdf.format(new java.util.Date())} - organization='$organization'")
    contributorsGrouped
  }

}
