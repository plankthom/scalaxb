/*
 * Copyright (c) 2010-2011 e.e d3si9n
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package scalaxb.compiler.xsd2

import scala.xml.Node
import scalaxb.compiler.{Config, Snippet}
import xmlschema._
import Defs._

class Generator(val schema: ReferenceSchema, 
    val context: SchemaContext, val config: Config) extends Params with PackageNamer
    with Namer with Lookup with Splitter {
  import com.weiglewilczek.slf4s.{Logger}
  private lazy val logger = Logger("xsd2.Generator")
  
  def generateEntitySource: Snippet =
    Snippet(
      headerSnippet ::
      (schema.unbound flatMap {
        case x: TaggedComplexType => processComplexType(x)
        case x: TaggedSimpleType if containsEnumeration(x) && isRootEnumeration(x) => processSimpleType(x)
        case _ => Nil
      }).toList: _*)

  def processComplexType(decl: Tagged[XComplexType]): List[Snippet] =
    List(generateComplexTypeEntity(buildTypeName(decl), decl))

  def generateComplexTypeEntity(name: QualifiedName, decl: Tagged[XComplexType]) = {
    val localName = name.localPart
    val list = splitParticlesIfLong(decl.particles)(decl.tag)
    val paramList: Seq[Param] = Param.fromList(list)
    val pseq = decl.primarySequence
    val compositors =  decl.compositors flatMap {splitIfLongSequence} filter {Some(_) != pseq}
    val compositorCodes = compositors.toList map {generateCompositor}
    val hasSequenceParam = (paramList.size == 1) && (paramList.head.occurrence.isMultiple) &&
          (!paramList.head.attribute) && (!decl.mixed) // && (!longAll)
    val paramsString =
      if (hasSequenceParam) makeParamName(paramList.head.name) + ": " + paramList.head.singleTypeName + "*"
      else paramList.map(_.toScalaCode).mkString(", " + NL + indent(1))

    Snippet(Snippet(<source>case class { localName }({paramsString})</source>) :: compositorCodes: _*)
  }

  def generateSequence(decl: Tagged[KeyedGroup]): Snippet = {
    implicit val tag = decl.tag
    val name = names.get(decl) getOrElse {"??"}
//      val superNames: List[String] = buildOptions(compositor)
//      val superString = if (superNames.isEmpty) ""
//        else " extends " + superNames.mkString(" with ")
    val list = splitParticlesIfLong(decl.particles)
    val paramList = Param.fromList(list)
    Snippet(<source>case class { name }({
      paramList.map(_.toScalaCode).mkString(", " + NL + indent(1))})</source>)
  }

  def generateCompositor(decl: Tagged[KeyedGroup]): Snippet = decl.key match {
    case "sequence" => generateSequence(decl)
    case _ =>
//      val superNames: List[String] = buildOptions(compositor)
//      val superString = if (superNames.isEmpty) ""
//        else " extends " + superNames.mkString(" with ")
      val superString = ""
      val name = names.get(decl) getOrElse {"??"}
      Snippet(<source>trait {name}{superString}</source>)
  }

  def processSimpleType(decl: Tagged[XSimpleType]): List[Snippet] =
    List(generateSimpleType(buildTypeName(decl), decl))

  def generateSimpleType(name: QualifiedName, decl: Tagged[XSimpleType]) = {
    val localName = name.localPart
    val enumValues = filterEnumeration(decl) map { enum =>
      val name = buildTypeName(enum)
      """case object %s extends %s { override def toString = "%s" }""".format(
        name.localPart, localName, enum.value.value)
    }

    Snippet(<source>trait { localName }
{ enumValues.mkString(NL) }</source>)
  }

  def headerSnippet: Snippet =
    Snippet(<source>// Generated by scalaxb.</source> +: packageCode, Nil, Nil, Nil)

  def packageCode: Seq[Node] =
    (packageNameByURI(schema.targetNamespace, context) map { pkg =>
      <source>package {pkg}</source>
    }).toSeq
}
