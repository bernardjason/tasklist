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
      "id" -> default(longNumber, 0L),
      "user" -> text.verifying("need username for add or update", { !_.isEmpty }),
      "password" -> text,
      "nickname" -> text.verifying("need nickname for add or update", { !_.isEmpty }),
      "role" -> optional(text))(User.apply)(User.unpick))

  val taskForm = Form(
    mapping(
      "name" -> text,
      "code" -> text)(Tasks.apply)(Tasks.unapplyit))

  val taskAdminForm = Form(
    mapping(
      "id" -> default(longNumber, 0L),
      "name" -> text,
      "code" -> text)(Tasks.apply)(Tasks.unapplyitadmin))

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
    Logger.info(s"not logged in")
    Redirect(routes.Application.list()).withNewSession
  } 

  def getAuth(implicit request: play.api.mvc.Request[play.api.mvc.AnyContent]): Option[User] = {
    request.session.get("user").map { u =>
      Logger.info(s"session user is ${u}")
      return cache.get[User](u)
    }
    None
  }

  def useradmin = Action { implicit request =>
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

    getAuth.map { auth =>
      Future {
        val list = getAlltheTasks
        Ok(views.html.list(loginForm, auth, list.toList))
      }
    }.getOrElse { Future.successful( Ok(views.html.list(loginForm, null,null))) }
  }
 
  def getAlltheTasks = {
      val q = tasks.result
      val list = Await.result(db.run(q), Duration.Inf).map { u =>
          Logger.debug(s"${u.id}, ${u.code},${u.name}")
          u
      }
      list
  }
   def taskadmin = Action { implicit request =>
    getAuth.map { u =>
      val list = getAlltheTasks
      Ok(views.html.taskadmin(loginForm,taskAdminForm, u, list.toList))
    }.getOrElse { notLoggedIn }
  }
  
  val newtask = Action.async { implicit request =>

    taskAdminForm.bindFromRequest.fold(
      formWithErrors => {
        getAuth.map { u =>
          Future.successful(BadRequest(views.html.taskadmin(loginForm,formWithErrors, u, getAlltheTasks.toList)))
        }.getOrElse {
          Future.successful(notLoggedIn)
        }
      },
      newtaskData => {

        def handleDbResponse(res: Try[Int]) = res match {
          case Success(res) => Redirect(routes.Application.taskadmin())
          case Failure(e) => {
            Logger.error(s"Problem on update " + res)
            val flasherr = s"Error " + res
            Redirect(routes.Application.taskadmin).flashing("error" -> flasherr)
          }
          case _ => {
            Redirect(routes.Application.taskadmin())
          }
        }

        getAuth.map { u =>
          if (newtaskData.id > 0) {
            Logger.info("Update "+newtaskData)
            if (newtaskData.id > 0) {
              val q = tasks.filter { u => u.id === newtaskData.id }
              db.run((q.update(newtaskData)).asTry).map { handleDbResponse(_) }

            } else {
              val q = tasks.filter(u => u.id === newtaskData.id).map(x => (x.name, x.code))
              val u = (newtaskData.name, newtaskData.code)
              db.run((q.update(u)).asTry).map { handleDbResponse(_) }
            }

          } else {
            Logger.info("Insert "+newtaskData)
            db.run((tasks += newtaskData).asTry).map { handleDbResponse(_) }
          }
        }.getOrElse { Future.successful(Redirect(routes.Application.list()).withNewSession) }

      })
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
          case Success(res) => Redirect(routes.Application.useradmin())
          case Failure(e) => {
            Logger.error(s"Problem on update " + res)
            val flasherr = s"Error " + res
            Redirect(routes.Application.useradmin).flashing("error" -> flasherr)
          }
          case _ => {
            Redirect(routes.Application.useradmin())
          }
        }

        getAuth.map { u =>
          if (newuserData.id > 0) {
            Logger.info("Update "+newuserData.id+" "+newuserData.user+" "+newuserData.nickname+" "+newuserData.role)
            if (newuserData.password.length > 0) {
              val q = users.filter { u => u.id === newuserData.id }
              db.run((q.update(newuserData)).asTry).map { handleDbResponse(_) }

            } else {
              val q = users.filter(u => u.id === newuserData.id).map(x => (x.user, x.nickname, x.role))
              val u = (newuserData.user, newuserData.nickname, newuserData.role)
              db.run((q.update(u)).asTry).map { handleDbResponse(_) }
            }

          } else {
            Logger.info("Insert "+newuserData)
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
      Logger.info(s"login is is ${id}")
      Redirect(routes.Application.list()).withSession( "user" -> id)
    }

  }

  val logout = Action {
    Redirect(routes.Application.list()).withNewSession
  }
}