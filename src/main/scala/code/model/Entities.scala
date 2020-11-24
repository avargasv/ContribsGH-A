package code.model

import net.liftweb.json.JsonDSL._

object Entities {

  type Organization = String

  case class Repository(name: String) extends AnyVal

  case class Contributor(repo: String, name: String, contributions: Int) {
    def asJson = {
      ("repo" -> repo) ~
      ("name" -> name) ~
      ("contributions" -> contributions)
    }
  }

}
