package controllers

import scala.concurrent.Await
import scala.concurrent.duration.Duration

import javax.inject.Inject
import models.User
import play.api.Logger
import play.api.cache.SyncCacheApi
import play.api.data.Form
import play.api.data.Forms.mapping
import play.api.data.Forms.text
import play.api.db.slick.DatabaseConfigProvider
import play.api.db.slick.HasDatabaseConfig
import play.api.mvc.AbstractController
import play.api.mvc.ControllerComponents
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

class Application @Inject() (components: ControllerComponents,cache: SyncCacheApi, protected val dbConfigProvider: DatabaseConfigProvider)
  extends AbstractController(components) with tables.UserTable with tables.TasksTable with HasDatabaseConfig[JdbcProfile] {

  override val dbConfig: DatabaseConfig[JdbcProfile] = dbConfigProvider.get[JdbcProfile]

  val logger: Logger = Logger(this.getClass())
  
  import dbConfig.profile.api._

  val users = TableQuery[Users]
  val tasks = TableQuery[ATask]

  val loginForm = Form(
    mapping(
      "user" -> text,
      "password" -> text,
      "nickname" -> text)(User.apply)(User.unpick))

  def debug = Action {
    println("*****************************************************************************")
    println("*    DEBUG LOGIN                                                            *")
    println("*****************************************************************************")
    val id = java.util.UUID.randomUUID().toString
    val u = User(1, "bernard", "jason", "Dont call me Bernie")
    cache.set(id, u)
    Redirect(routes.Application.list()).withSession(
      "user" -> id)

  }

  def javascript = Action { implicit request =>
    val user = request.session.get("user")

    request.session.get("user").map { u =>
      val auth = cache.get[User](u)
      if (!auth.isEmpty)
        Ok(views.js.javascript(auth.get))
      else
        Ok(views.js.javascript(null))
    }.getOrElse {
      Ok(views.js.javascript(null))
    }
  }

  def index = Action {
    Redirect(routes.Application.list())
  }

  def list = Action { implicit request =>

    val q = tasks.result
    val list = Await.result(db.run(q), Duration.Inf).map { u =>

      logger.debug(s"${u.id}, ${u.code},${u.name}")
      u
    }

    request.session.get("user").map { u =>
      logger.info(s"session user is ${u}")
      val auth = cache.get[User](u)
      logger.info(s"search logged on is ${auth}")
      if (!auth.isEmpty)
        Ok(views.html.list(loginForm, auth.get.user, list.toList))
      else
        Ok(views.html.list(loginForm, null, null))
    }.getOrElse {
      Ok(views.html.list(loginForm, null, null))
    }
  }

  val login = Action(parse.form(loginForm)) {
    implicit request =>

      val loginData = request.body

      val q = users.filter { u => u.user === loginData.user && u.password === loginData.password }

      var id: String = ""
      Await.result(db.run(q.result), Duration.Inf).map { u =>

        id = java.util.UUID.randomUUID().toString

        cache.set(id, u)
      }

      logger.info(s"login is is ${id}")
      Redirect(routes.Application.list()).withSession(
        "user" -> id)

  }

  val logout = Action {
    Redirect(routes.Application.list()).withNewSession
  }
}
