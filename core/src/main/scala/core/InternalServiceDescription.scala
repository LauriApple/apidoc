package core

import play.api.libs.json._

/**
 * Just parses json with minimal validation - build to provide a way to
 * generate meaningful validation messages back to user. Basic flow
 *
 * JSON => InternalServiceDescription => ServiceDescription
 *
 */
private[core] object InternalServiceDescription {

  def apply(apiJson: String): InternalServiceDescription = {
    val jsValue = Json.parse(apiJson)
    InternalServiceDescription(jsValue)
  }

}

private[core] case class InternalServiceDescription(json: JsValue) {

  lazy val name = (json \ "name").asOpt[String]
  lazy val baseUrl = (json \ "base_url").asOpt[String]
  lazy val basePath = (json \ "base_path").asOpt[String]
  lazy val description = (json \ "description").asOpt[String]

  lazy val models: Seq[InternalModel] = {
    (json \ "models").asOpt[JsObject] match {
      case None => Seq.empty
      case Some(models: JsObject) => {
        models.fields.map { v =>
          v match {
            case(key, value) => InternalModel(key, value.as[JsObject])
          }
        }
      }
    }
  }

  lazy val enums: Seq[InternalEnum] = {
    (json \ "enums").asOpt[JsObject] match {
      case None => Seq.empty
      case Some(enums: JsObject) => {
        enums.fields.map { v =>
          v match {
            case(key, value) => InternalEnum(key, value.as[JsObject])
          }
        }
      }
    }
  }

  lazy val resources: Seq[InternalResource] = {
    (json \ "resources").asOpt[JsValue] match {
      case None => Seq.empty

      // Array is deprecated - JsObject is preferred
      case Some(resources: JsArray) => {
        resources.value.flatMap { v =>
          v match {
            case o: JsObject => {
              val modelName = (o \ "model").asOpt[String]
              Some(InternalResource(modelName, models, o))
            }
            case _ => None
          }
        }
      }

      case Some(resources: JsObject) => {
        resources.fields.map { v =>
          v match {
            case(modelName, value) => InternalResource(Some(modelName), models, value.as[JsObject])
          }
        }
      }

      case _ => Seq.empty
    }
  }

}

case class InternalModel(
  name: String,
  plural: String,
  description: Option[String],
  fields: Seq[InternalField]
)

case class InternalEnum(
  name: String,
  description: Option[String],
  values: Seq[InternalEnumValue]
)

case class InternalEnumValue(
  name: Option[String],
  description: Option[String]
)

case class InternalResource(modelName: Option[String],
                            path: String,
                            operations: Seq[InternalOperation])

case class InternalOperation(method: Option[String],
                             path: String,
                             description: Option[String],
                             namedPathParameters: Seq[String],
                             parameters: Seq[InternalParameter],
                             body: Option[String],
                             responses: Seq[InternalResponse]) {

  lazy val label = "%s %s".format(method.getOrElse(""), path)

}

case class InternalField(name: Option[String] = None,
                         fieldtype: Option[String] = None,
                         description: Option[String] = None,
                         required: Boolean = true,
                         multiple: Boolean = false,
                         default: Option[String] = None,
                         example: Option[String] = None,
                         minimum: Option[Long] = None,
                         maximum: Option[Long] = None)

case class InternalParameter(name: Option[String] = None,
                             paramtype: Option[String] = None,
                             description: Option[String] = None,
                             required: Boolean = true,
                             multiple: Boolean = false,
                             default: Option[String] = None,
                             example: Option[String] = None,
                             minimum: Option[Long] = None,
                             maximum: Option[Long] = None)


case class InternalResponse(code: String,
                            datatype: Option[String] = None,
                            multiple: Boolean = false) {

  lazy val datatypeLabel: Option[String] = datatype.map { dt =>
    if (multiple) {
      s"[$dt]"
    } else {
      dt
    }
  }

}

object InternalModel {

  def apply(name: String, value: JsObject): InternalModel = {
     val description = (value \ "description").asOpt[String]
     val plural: String = (value \ "plural").asOpt[String].getOrElse( Text.pluralize(name) )

     val fields = (value \ "fields").asOpt[JsArray] match {

       case None => Seq.empty

       case Some(a: JsArray) => {
         a.value.map { json => InternalField(json.as[JsObject]) }
       }

     }


    InternalModel(name = name,
                  plural = plural,
                  description = description,
                  fields = fields)
  }

}

object InternalEnum {

  def apply(name: String, value: JsObject): InternalEnum = {
    val description = (value \ "description").asOpt[String]
    val values = (value \ "values").asOpt[JsArray] match {
      case None => Seq.empty
      case Some(a: JsArray) => a.value.map { json =>
        InternalEnumValue(
          name = (json \ "name").asOpt[String],
          description = (json \ "description").asOpt[String]
        )
      }
    }

    InternalEnum(
      name = name,
      description = description,
      values = values
    )
  }

}

object InternalResource {

  def apply(modelName: Option[String], models: Seq[InternalModel], value: JsObject): InternalResource = {
    val path = (value \ "path").asOpt[String].getOrElse {
      models.find(m => Some(m.name) == modelName) match {
        case Some(model: InternalModel) => "/" + model.plural
        case None => "/"
      }
    }

    val operations = (value \ "operations").asOpt[JsArray] match {
      case None => Seq.empty
      case Some(a: JsArray) => {
        a.value.map { json => InternalOperation(path, json.as[JsObject]) }
      }
    }

    InternalResource(modelName = modelName,
                     path = path,
                     operations = operations)
  }

}

object InternalOperation {

  private val NoContentResponse = InternalResponse(code = "204", datatype = Some(Datatype.UnitType.name))

  def apply(resourcePath: String, json: JsObject): InternalOperation = {
    val path = resourcePath + (json \ "path").asOpt[String].getOrElse("")
    val namedPathParameters = Util.namedParametersInPath(path)
    val parameters = (json \ "parameters").asOpt[JsArray] match {
      case None => Seq.empty
      case Some(a: JsArray) => {
        a.value.map { data => InternalParameter(data.as[JsObject]) }
      }
    }

    val responses: Seq[InternalResponse] = {
      (json \ "responses").asOpt[JsObject] match {
        case None => {
          Seq(NoContentResponse)
        }

        case Some(responses: JsObject) => {
          responses.fields.map {
            case(code, value) => {
              InternalResponse(code, value.as[JsObject])
            }
          }
        }
      }
    }

    val body = (json \ "body").asOpt[JsObject] match {
      case None => None
      case Some(body: JsObject) => (body \ "type").asOpt[String]
    }

    InternalOperation(method = (json \ "method").asOpt[String].map(_.toUpperCase),
                      path = path,
                      body = body,
                      description = (json \ "description").asOpt[String],
                      responses = responses,
                      namedPathParameters = namedPathParameters,
                      parameters = parameters)
  }

}

object InternalResponse {

  def apply(code: String, json: JsObject): InternalResponse = {
    val parsedDatatype = (json \ "type").asOpt[String].map( InternalParsedDatatype(_) )
    InternalResponse(code = code,
                     datatype = parsedDatatype.map(_.name),
                     multiple = parsedDatatype.map(_.multiple).getOrElse(false))
  }
}

object InternalField {

  def apply(json: JsObject): InternalField = {
    val parsedDatatype = (json \ "type").asOpt[String].map( InternalParsedDatatype(_) )

    InternalField(name = (json \ "name").asOpt[String],
                  fieldtype = parsedDatatype.map(_.name),
                  description = (json \ "description").asOpt[String],
                  required = JsonStringParser.asOptBoolean(json \ "required").getOrElse(true),
                  multiple = parsedDatatype.map(_.multiple).getOrElse(false),
                  default = JsonStringParser.asOptString(json, "default"),
                  minimum = (json \ "minimum").asOpt[Long],
                  maximum = (json \ "maximum").asOpt[Long],
                  example = JsonStringParser.asOptString(json, "example"))
  }

}

object InternalParameter {

  def apply(json: JsObject): InternalParameter = {
    val dt = (json \ "type").asOpt[String].map( InternalParsedDatatype(_) )

    InternalParameter(name = (json \ "name").asOpt[String],
                      paramtype = dt.map(_.name),
                      description = (json \ "description").asOpt[String],
                      required = JsonStringParser.asOptBoolean(json \ "required").getOrElse(true),
                      multiple = dt.map(_.multiple).getOrElse(false),
                      default = JsonStringParser.asOptString(json, "default"),
                      minimum = (json \ "minimum").asOpt[Long],
                      maximum = (json \ "maximum").asOpt[Long],
                      example = JsonStringParser.asOptString(json, "example"))
  }

}

/**
 * Parse numbers and string json values as strings
 */
private[core] object JsonStringParser {

  def asOptString(json: JsValue, field: String): Option[String] = {
    val value = (json \ field)
    asOptString(value)
  }

  def asOptString(value: JsValue): Option[String] = {
    value match {
      case (_: JsUndefined) => None
      case (v: JsString) => Some(v.value)
      case (v: JsValue) => Some(v.toString)
    }
  }

  def asOptBoolean(value: JsValue): Option[Boolean] = {
    asOptString(value).map(_ == "true")
  }

}

private[core] case class InternalParsedDatatype(name: String, multiple: Boolean)

private[core] object InternalParsedDatatype {

  private val ArrayRx = "^\\[(.*)\\]$".r

  def apply(value: String): InternalParsedDatatype = {
    value match {
      case ArrayRx(word) => InternalParsedDatatype(word, true)
      case _ => InternalParsedDatatype(value, false)
    }
  }

}
