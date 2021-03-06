package core.generator

import codegenerator.models.ServiceDescription
import core._
import Text._

case class PlayFrameworkVersion(
  name: String,
  config: ScalaClientMethodConfig,
  requestHolderClass: String,
  authSchemeClass: String,
  supportsHttpPatch: Boolean
)

object PlayFrameworkVersions {

  val V2_2_x = PlayFrameworkVersion(
    name = "2.2.x",
    config = ScalaClientMethodConfigs.Play22,
    requestHolderClass = "play.api.libs.ws.WS.WSRequestHolder",
    authSchemeClass = "com.ning.http.client.Realm.AuthScheme",
    supportsHttpPatch = false
  )

  val V2_3_x = PlayFrameworkVersion(
    name = "2.3.x",
    config = ScalaClientMethodConfigs.Play23,
    requestHolderClass = "play.api.libs.ws.WSRequestHolder",
    authSchemeClass = "play.api.libs.ws.WSAuthScheme",
    supportsHttpPatch = true
  )
}

object Play22ClientGenerator extends CodeGenerator {
  override def generate(ssd: ScalaServiceDescription, userAgent: String): String = {
    Play2ClientGenerator.generate(PlayFrameworkVersions.V2_2_x, ssd, userAgent)
  }
}

object Play23ClientGenerator extends CodeGenerator {
  override def generate(ssd: ScalaServiceDescription, userAgent: String): String = {
    Play2ClientGenerator.generate(PlayFrameworkVersions.V2_3_x, ssd, userAgent)
  }
}

object Play2ClientGenerator {

  def generate(version: PlayFrameworkVersion, sd: ServiceDescription, userAgent: String): String = {
    val ssd = new ScalaServiceDescription(sd)
    generate(version, ssd, userAgent)
  }

  def generate(version: PlayFrameworkVersion, ssd: ScalaServiceDescription, userAgent: String): String = {
    Play2ClientGenerator(version, ssd, userAgent).generate()
  }

}

case class Play2ClientGenerator(version: PlayFrameworkVersion, ssd: ScalaServiceDescription, userAgent: String) {

  def generate(): String = {
    Seq(
      Play2Models(ssd),
      client()
    ).mkString("\n\n")
  }

  private def client(): String = {

    val methodGenerator = ScalaClientMethodGenerator(version.config, ssd)

    val patchMethod = version.supportsHttpPatch match {
      case true => """_logRequest("PATCH", _requestHolder(path).withQueryString(queryParameters:_*)).patch(body.getOrElse(play.api.libs.json.Json.obj()))"""
      case false => s"""sys.error("PATCH method is not supported in Play Framework Version ${version.name}")"""
    }

    val headerString = ".withHeaders(" +
      (ssd.defaultHeaders ++ Seq(ScalaHeader("User-Agent", "UserAgent"))).map { h =>
        s""""${h.name}" -> ${h.quotedValue}"""
      }.mkString(", ") + ")"

    s"""package ${ssd.packageName} {
  object helpers {

    import play.api.mvc.QueryStringBindable
${ScalaHelpers.dateTime}

    implicit object DateTimeISOQueryStringBinder extends QueryStringBindable[DateTime] {
      override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, DateTime]] = {
        for {
          values <- params.get(key)
          s <- values.headOption
        } yield parseDateTimeISO(s)
      }

      override def unbind(key: String, time: DateTime): String = key + "=" + dateTimeISOFormatter.print(time)
    }
  }

  class Client(apiUrl: String, apiToken: scala.Option[String] = None) {
    import ${ssd.modelPackageName}.json._

    private val UserAgent = "$userAgent"
    private val logger = play.api.Logger("${ssd.packageName}.client")

    logger.info(s"Initializing ${ssd.packageName}.client for url $$apiUrl")

${methodGenerator.accessors().indent(4)}

${methodGenerator.objects().indent(4)}

    def _requestHolder(path: String): ${version.requestHolderClass} = {
      import play.api.Play.current

      val holder = play.api.libs.ws.WS.url(apiUrl + path)$headerString
      apiToken.fold(holder) { token =>
        holder.withAuth(token, "", ${version.authSchemeClass}.BASIC)
      }
    }

    def _logRequest(method: String, req: ${version.requestHolderClass})(implicit ec: scala.concurrent.ExecutionContext): ${version.requestHolderClass} = {
      val queryComponents = for {
        (name, values) <- req.queryString
        value <- values
      } yield name -> value
      val url = s"$${req.url}$${queryComponents.mkString("?", "&", "")}"
      apiToken.fold(logger.info(s"curl -X $$method $$url")) { _ =>
        logger.info(s"curl -X $$method -u '[REDACTED]:' $$url")
      }
      req
    }

    def _executeRequest(
      method: String,
      path: String,
      queryParameters: Seq[(String, String)] = Seq.empty,
      body: Option[play.api.libs.json.JsValue] = None
    )(implicit ec: scala.concurrent.ExecutionContext): scala.concurrent.Future[${version.config.responseClass}] = {
      method.toUpperCase match {
        case "GET" => {
          _logRequest("GET", _requestHolder(path).withQueryString(queryParameters:_*)).get()      
        }
        case "POST" => {
          _logRequest("POST", _requestHolder(path).withQueryString(queryParameters:_*)).post(body.getOrElse(play.api.libs.json.Json.obj()))
        }
        case "PUT" => {
          _logRequest("PUT", _requestHolder(path).withQueryString(queryParameters:_*)).put(body.getOrElse(play.api.libs.json.Json.obj()))
        }
        case "PATCH" => {
          $patchMethod
        }
        case "DELETE" => {
          _logRequest("DELETE", _requestHolder(path).withQueryString(queryParameters:_*)).delete()
        }
        case _ => {
          _logRequest(method, _requestHolder(path).withQueryString(queryParameters:_*))
          sys.error("Unsupported method[%s]".format(method))
        }
      }
    }

  }

${methodGenerator.traitsAndErrors().indent(2)}

}"""
  }

}
