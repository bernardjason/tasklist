package controllers

import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

import javax.inject.Inject
import models.Tasks
import models.User
import play.api.Logger
import play.api.cache.SyncCacheApi
import play.api.data.Form
import play.api.data.Forms._
import play.api.data.Forms.mapping
import play.api.data.Forms.text
import play.api.db.slick.DatabaseConfigProvider
import play.api.db.slick.HasDatabaseConfig
import play.api.mvc.AbstractController
import play.api.mvc.ControllerComponents
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.concurrent.Future
import play.mvc.Result

class Application @Inject() (implicit ec: ExecutionContext, components: ControllerComponents,
                             cache: SyncCacheApi, protected val dbConfigProvider: DatabaseConfigProvider)
  extends AbstractController(components) with tables.UserTable with tables.TasksTable with HasDatabaseConfig[JdbcProfile]
  with play.api.i18n.I18nSupport {

  override val dbConfig: DatabaseConfig[JdbcProfile] = dbConfigProvider.get[JdbcProfile]

  val logger: Logger = Logger(this.getClass())

  import dbConfig.profile.api._

  val users = TableQuery[Users]
  val tasks = TableQuery[ATask]

  val loginForm = Form(
    mapping(
      "user" -> text,
      "password" -> nonEmptyText,
      "nickname" -> nonEmptyText)(User.apply)(User.unpicknorole))

  val loginAdminForm = Form(
    mapping(
      "id" -> default(number, 0),
      "user" -> text.verifying("need username for add or update", { !_.isEmpty }),
      "password" -> text,
      "nickname" -> text.verifying("need nickname for add or update", { !_.isEmpty }),
      "role" -> optional(text))(User.apply)(User.unpick))

  val taskForm = Form(
    mapping(
      "name" -> text,
      "code" -> text)(Tasks.apply)(Tasks.unapplyit))

  def debug = Action {
    println("*****************************************************************************")
    println("*    DEBUG LOGIN                                                            *")
    println("*****************************************************************************")
    val id = java.util.UUID.randomUUID().toString
    val u = User(1, "admin", "admin", "Dont call me Bernie", Some("admin"))
    cache.set(id, u)
    Redirect(routes.Application.list()).withSession(
      "user" -> id)

  }
  def getAllTheUsers = {
    val q = users.sortBy(f => f.user)
    val allUsers = new ListBuffer[User]

    Await.result(db.run(q.result), Duration.Inf).map { u =>
      allUsers += u
    }
    allUsers
  }

  def notLoggedIn(implicit request: play.api.mvc.Request[play.api.mvc.AnyContent]) = {
    logger.info(s"not logged in")
    Redirect(routes.Application.list()).withNewSession
  } 

  def getAuth(implicit request: play.api.mvc.Request[play.api.mvc.AnyContent]): Option[User] = {
    request.session.get("user").map { u =>
      logger.info(s"session user is ${u}")
      return cache.get[User](u)
    }
    None
  }

  def admin = Action { implicit request =>
    getAuth.map { u =>
      Ok(views.html.admin(loginAdminForm, taskForm, u, getAllTheUsers.toList))
    }.getOrElse { notLoggedIn }

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

  def list = Action.async { implicit request =>

    val q = tasks.result

    getAuth.map { auth =>
      Future {
        val list = Await.result(db.run(q), Duration.Inf).map { u =>
          logger.debug(s"${u.id}, ${u.code},${u.name}")
          u
        }
        Ok(views.html.list(loginForm, auth, list.toList))
      }
    }.getOrElse { Future.successful(notLoggedIn) }
  }

  val newuser = Action.async { implicit request =>

    loginAdminForm.bindFromRequest.fold(
      formWithErrors => {
        getAuth.map { u =>
          Future.successful(BadRequest(views.html.admin(formWithErrors, taskForm, u, getAllTheUsers.toList)))
        }.getOrElse {
          Future.successful(notLoggedIn)
        }
      },
      newuserData => {

        def handleDbResponse(res: Try[Int]) = res match {
          case Success(res) => Redirect(routes.Application.admin())
          case Failure(e) => {
            logger.error(s"Problem on update " + res)
            val flasherr = s"Error " + res
            Redirect(routes.Application.admin).flashing("error" -> flasherr)
          }
          case _ => {
            Redirect(routes.Application.admin())
          }
        }

        getAuth.map { u =>
          if (newuserData.id > 0) {
            if (newuserData.password.length > 0) {
              val q = users.filter { u => u.id === newuserData.id }
              db.run((q.update(newuserData)).asTry).map { handleDbResponse(_) }

            } else {
              val q = users.filter(u => u.id === newuserData.id).map(x => (x.user, x.nickname, x.role))
              val u = (newuserData.user, newuserData.nickname, newuserData.role)
              db.run((q.update(u)).asTry).map { handleDbResponse(_) }
            }

          } else {
            db.run((users += newuserData).asTry).map { handleDbResponse(_) }
          }
        }.getOrElse { Future.successful(Redirect(routes.Application.list()).withNewSession) }

      })
  }

  val login = Action(parse.form(loginForm)).async { implicit request =>

    val loginData = request.body

    val q = users.filter { u => u.user === loginData.user && u.password === loginData.password }

    Future{
      var id: String = ""
      Await.result(db.run(q.result), Duration.Inf).map { u =>
        id = java.util.UUID.randomUUID().toString
        cache.set(id, u)
      }
      logger.info(s"login is is ${id}")
      Redirect(routes.Application.list()).withSession( "user" -> id)
    }

  }

  val logout = Action {
    Redirect(routes.Application.list()).withNewSession
  }
}