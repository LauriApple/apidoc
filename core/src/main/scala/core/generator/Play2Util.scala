package core.generator

import codegenerator.models._
import core._
import Text._

case class Play2Util(config: ScalaClientMethodConfig) {
  import ScalaDataType._

  def params(
    fieldName: String,
    params: Seq[ScalaParameter]
  ): Option[String] = {
    if (params.isEmpty) {
      None
    } else {
      val arrayParams = params.filter(_.multiple) match {
        case Nil => Seq.empty
        case params => {
          params.map { p =>
            s"""  ${p.name}.map("${p.originalName}" -> ${ScalaDataType.asString("_", p.baseType)})"""
          }
        }
      }
      val arrayParamString = arrayParams.mkString(" ++\n")

      val singleParams = params.filter(!_.multiple) match {
        case Nil => Seq.empty
        case params => {
          Seq(
            s"val $fieldName = Seq(",
            params.map { p =>
              if (p.isOption) {
                s"""  ${p.name}.map("${p.originalName}" -> ${ScalaDataType.asString("_", p.baseType)})"""
              } else {
                s"""  Some("${p.originalName}" -> ${ScalaDataType.asString(p.name, p.baseType)})"""
              }
            }.mkString(",\n"),
            ").flatten"
          )
        }
      }
      val singleParamString = singleParams.mkString("\n")

      Some(
        if (singleParams.isEmpty) {
          s"val $fieldName = " + arrayParamString.trim
        } else if (arrayParams.isEmpty) {
          singleParamString
        } else {
          singleParamString + " ++\n" + arrayParamString
        }
      )
    }
  }
  
  def pathParams(op: ScalaOperation): String = {
    val pairs = op.pathParameters.map { p =>
      require(!p.multiple, "Path parameters cannot be lists.")
      p.originalName -> PathParamHelper.urlEncode(p.name, p.datatype)
    }
    val tmp: String = pairs.foldLeft(op.path) {
      case (path, (name, value)) =>
        val spec = s"/:$name"
        val from = path.indexOfSlice(spec)
        path.patch(from, s"/$${$value}", spec.length)
    }
    s""" s"$tmp" """.trim
  }

  def formBody(op: ScalaOperation): Option[String] = {
    // Can have both or form params but not both as we can only send a single document
    assert(op.body.isEmpty || op.formParameters.isEmpty)

    if (op.formParameters.isEmpty && op.body.isEmpty) {
      None

    } else if (!op.body.isEmpty) {
      val payload = op.body.get.body match {
        case Type(TypeKind.Primitive, name, multiple) => ScalaDataType.asString(ScalaUtil.toVariable(op.body.get.name, multiple), ScalaDataType(Datatype.forceByName(name)))
        case Type(TypeKind.Model, name, multiple) => ScalaUtil.toVariable(op.body.get.name, multiple)
        case Type(TypeKind.Enum, name, multiple) => s"${ScalaUtil.toVariable(op.body.get.name, multiple)}.map(_.toString)"
      }

      Some(s"val payload = play.api.libs.json.Json.toJson($payload)")

    } else {
      val params = op.formParameters.map { param =>
        s""" "${param.originalName}" -> play.api.libs.json.Json.toJson(${param.name})""".trim
      }.mkString(",\n")
      Some(
        Seq(
          "val payload = play.api.libs.json.Json.obj(",
          params.indent,
          ")"
        ).mkString("\n")
      )
    }
  }

  private object PathParamHelper {
    def urlEncode(
      name: String,
      d: ScalaDataType
    ): String = {
      d match {
        case ScalaStringType => s"""${config.pathEncodingMethod}($name, "UTF-8")"""
        case ScalaIntegerType | ScalaDoubleType | ScalaLongType | ScalaBooleanType | ScalaDecimalType | ScalaUuidType => name
        case ScalaEnumType(_, _) => s"""${config.pathEncodingMethod}($name.toString, "UTF-8")"""
        case ScalaDateIso8601Type => s"$name.toString"
        case ScalaDateTimeIso8601Type => s"$name.toString" // TODO
        case ScalaListType(_) | ScalaMapType | ScalaModelType(_, _) | ScalaOptionType(_) | ScalaUnitType => {
          sys.error(s"Cannot encode params of type[$d] as path parameters (name: $name)")
        }
      }
    }
  }

}
