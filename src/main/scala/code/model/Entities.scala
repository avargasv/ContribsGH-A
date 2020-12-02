package code.model

import net.liftweb.json.JsonDSL._
import java.time.Instant

object Entities {

  type Organization = String

  case class Repository(name: String, updatedAt: Instant)

  case class Contributor(repo: String, name: String, contributions: Int) {
    def asJson = {
      ("repo" -> repo) ~
      ("name" -> name) ~
      ("contributions" -> contributions)
    }
  }

}
