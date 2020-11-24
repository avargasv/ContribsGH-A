package code.restService

import code.lib.AppAux._
import code.model.Entities._

object RestClient {

  def reposByOrganization(organization: Organization): List[Repository] = {
    val resp = processResponseBody(s"https://api.github.com/orgs/$organization/repos") { responsePage =>
      val full_name_RE = s""","full_name":"$organization/([^"]+)",""".r
      val full_name_L = (for (full_name_RE(full_name) <- full_name_RE.findAllIn(responsePage)) yield full_name).toList
      full_name_L.map(Repository(_))
    }
    logger.info(s"# of repos=${resp.length}")
    resp
  }

  def contributorsByRepo(organization: Organization, repo: Repository): List[Contributor] = {

    val resp = processResponseBody(s"https://api.github.com/repos/$organization/${repo.name}/contributors") { responsePage =>
      val login_RE = """"login":"([^"]+)"""".r
      val login_I = for (login_RE(login) <- login_RE.findAllIn(responsePage)) yield login
      val contributions_RE = """"contributions":([0-9]+)""".r
      val contributions_I = for (contributions_RE(contributions) <- contributions_RE.findAllIn(responsePage)) yield contributions
      val contributors_L = login_I.zip(contributions_I).map(p => Contributor(repo.name, p._1, p._2.toInt)).toList
      contributors_L
    }
    logger.info(s"repo='${repo.name}', # of contributors=${resp.length}")
    resp
  }

  import akka.actor.ActorSystem
  import spray.http.StatusCodes
  import spray.client.pipelining.{Get, WithTransformation, addHeader, sendReceive}
  import spray.http.{HttpRequest, HttpResponse}

  import scala.annotation.tailrec
  import scala.concurrent.{Await, Future}

  def processResponseBody[T](url: String) (processPage: String => List[T]): List[T] = {

    @tailrec
    def processResponsePage(processedPages: List[T], page: Int): List[T] = {
      val eitherPageBody = getRestResponseBody(s"$url?page=$page&per_page=100")
      eitherPageBody match {
        case Right(pageBody) =>
          if (pageBody.length > 2) {
            val processedPage = processPage(pageBody)
            processResponsePage(processedPages ++ processedPage, page + 1)
          } else {
            processedPages
          }
        case Left(error) =>
          logger.info(s"processResponseBody error - $error")
          processedPages
      }
    }

    processResponsePage(List.empty[T], 1)
  }

  implicit val system = ActorSystem()
  import system.dispatcher

  val pipeline: HttpRequest => Future[HttpResponse] = sendReceive

  def getRestResponseBody(url: String): Either[String, String] = {
    val request =
      if (gh_token != null) Get(url) ~> addHeader("Authorization", gh_token)
      else Get(url)
    val response = Await.result(pipeline(request), timeout)
    response.status match {
      case StatusCodes.OK =>
        Right(response.entity.asString.trim)
      case StatusCodes.Forbidden =>
        Left("API rate limit exceeded")
      case StatusCodes.NotFound =>
        Left("Non-existent organization")
      case _ =>
        Left(s"Unexpected StatusCode ${response.status.intValue}")
    }
  }

}
