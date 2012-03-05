/*
 * Copyright (c) 2010 e.e d3si9n
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

import java.net.{URI}
import scala.xml.{NamespaceBinding}
import scalaxb._
import scalaxb.compiler.xsd.{XsTypeSymbol, XsAnyType}
import xmlschema._
import scala.collection.mutable.{Builder, ArrayBuffer}
import scala.collection.generic.CanBuildFrom
import scala.annotation.tailrec

object Defs {
  implicit def schemaToSchemaIteration(schema: XSchema): SchemaIteration = SchemaIteration(schema)
  implicit def complexTypeToComplexTypeOps(tagged: Tagged[XComplexType]): ComplexTypeOps = ComplexTypeOps(tagged)
  implicit def complexTypeToComplexTypeIteration(tagged: Tagged[XComplexType])(implicit schema: XSchema): ComplexTypeIteration =
    ComplexTypeIteration(tagged)
  implicit def elementToElementOps(tagged: Tagged[XElement]): ElementOps = new ElementOps(tagged)
  implicit def attributeGroupToAttributeGroupOps(tagged: Tagged[XAttributeGroup]): AttributeGroupOps =
    new AttributeGroupOps(tagged)
  implicit def namedGroupToNamedGroupOps(tagged: Tagged[XNamedGroup]): NamedGroupOps = NamedGroupOps(tagged)
  
  val XML_SCHEMA_URI = new URI("http://www.w3.org/2001/XMLSchema")
  val XSI_URL = new URI("http://www.w3.org/2001/XMLSchema-instance")
  val XSI_PREFIX = "xsi"
  val XML_URI = new URI("http://www.w3.org/XML/1998/namespace")
  val XML_PREFIX = "xml"
  val SCALA_URI = new URI("http://scala-lang.org/")
  val SCALAXB_URI = new URI("http://scalaxb.org/")
  val NL = System.getProperty("line.separator")

  val XS_ANY_TYPE = QualifiedName(XML_SCHEMA_URI, "anyType")
  val XS_ANY_SIMPLE_TYPE = QualifiedName(XML_SCHEMA_URI, "anySimpleType")
}

abstract class TopLevelType
case object SimpleTypeHost extends  TopLevelType
case object ComplexTypeHost extends TopLevelType
case object NamedGroupHost extends TopLevelType
case object AttributeGroupHost extends TopLevelType
case object ElementHost extends TopLevelType
case object AttributeHost extends TopLevelType
case class HostTag(namespace: Option[URI], topLevel: TopLevelType, name: String)
object HostTag {
  def apply(namespace: Option[URI], elem: XTopLevelElement): HostTag =
    HostTag(namespace, ElementHost, elem.name getOrElse {error("name is required.")})
  def apply(namespace: Option[URI], decl: XTopLevelSimpleType): HostTag =
    HostTag(namespace, SimpleTypeHost, decl.name getOrElse {error("name is required.")})
  def apply(namespace: Option[URI], decl: XTopLevelComplexType): HostTag =
    HostTag(namespace, ComplexTypeHost, decl.name getOrElse {error("name is required.")})
  def apply(namespace: Option[URI], attr: XTopLevelAttribute): HostTag =
    HostTag(namespace, AttributeHost, attr.name getOrElse {error("name is required.")})
  def apply(namespace: Option[URI], group: XNamedGroup): HostTag =
    HostTag(namespace, NamedGroupHost, group.name getOrElse {error("name is required.")})
  def apply(namespace: Option[URI], group: XNamedAttributeGroup): HostTag =
    HostTag(namespace, AttributeGroupHost, group.name getOrElse {error("name is required.")})
}

trait GroupOps {
  import Defs._

  def value: XGroup

  // List of TaggedElement, TaggedKeyedGroup, or TaggedAny.
  def particles(implicit tag: HostTag, lookup: Lookup, splitter: Splitter): Seq[TaggedParticle[_]] =
    value.arg1.toSeq flatMap {
      case DataRecord(_, _, x: XLocalElementable) =>
        Seq(TaggedLocalElement(x, lookup.schema.unbound.elementFormDefault, tag))
      case DataRecord(_, _, x: XGroupRef) => Seq(Tagged(x, tag))
      case DataRecord(_, Some(particleKey), x: XExplicitGroupable) =>
        if (particleKey == SequenceTag) KeyedGroup(particleKey, x).innerSequenceToParticles
        else Seq(Tagged(KeyedGroup(particleKey, x), tag))
      case DataRecord(_, _, x: XAny) => Seq(Tagged(x, tag))
    }
}

sealed trait CompositorKey
case object ChoiceTag extends CompositorKey { override def toString = "choice" }
case object SequenceTag extends CompositorKey { override def toString = "sequence" }
case object AllTag extends CompositorKey { override def toString = "all" }

object KeyedGroup {
  def apply(key: String, value: XExplicitGroupable): KeyedGroup = key match {
    case "choice"   => KeyedGroup(ChoiceTag, value)
    case "sequence" => KeyedGroup(SequenceTag, value)
    case "all"      => KeyedGroup(AllTag, value)
  }
}

case class KeyedGroup(key: CompositorKey, value: XExplicitGroupable) extends GroupOps {
  import Defs._

  def tagged(implicit tag: HostTag) = Tagged(this, tag)

  def innerSequenceToParticles(implicit tag: HostTag, lookup: Lookup, splitter: Splitter): Seq[TaggedParticle[_]] =
    if (value.minOccurs != 1 || value.maxOccurs != "1")
      if (value.arg1.length == 1) value.arg1(0) match {
        case DataRecord(_, Some(particleKey), any: XAny) =>
          Seq(Tagged(any.copy(
            minOccurs = math.min(any.minOccurs.toInt, value.minOccurs.toInt),
            maxOccurs = Occurrence.max(any.maxOccurs, value.maxOccurs)), tag))
        case DataRecord(_, Some("choice"), choice: XExplicitGroup) =>
          Seq(Tagged(KeyedGroup(ChoiceTag, choice.copy(
            minOccurs = math.min(choice.minOccurs.toInt, value.minOccurs.toInt),
            maxOccurs = Occurrence.max(choice.maxOccurs, value.maxOccurs)) ), tag))

        case _ => Seq(tagged)
      }
      else Seq(tagged)
    else splitter.splitIfLongSequence(tagged)
}

case class NamedGroupOps(group: Tagged[XNamedGroup]) extends GroupOps {
  val value = group.value
}

/** represents attributes param */
case class AttributeSeqParam() {}

/** represents param created for xs:all. */
case class AllParam() {}

sealed trait Tagged[+A] {
  def value: A
  def tag: HostTag
  override def toString: String = "Tagged(%s, %s)".format(value.toString, tag.toString)
}

sealed trait TaggedAttr[+A] extends Tagged[A] {}

object Tagged {
  def apply(value: XSimpleType, tag: HostTag): TaggedType[XSimpleType] = TaggedSimpleType(value, tag)
  def apply(value: XComplexType, tag: HostTag): TaggedType[XComplexType] = TaggedComplexType(value, tag)
  def apply(value: XNamedGroup, tag: HostTag): Tagged[XNamedGroup] = TaggedNamedGroup(value, tag)
  def apply(value: XGroupRef, tag: HostTag): TaggedParticle[XGroupRef] = TaggedGroupRef(value, tag)
  def apply(value: KeyedGroup, tag: HostTag): TaggedParticle[KeyedGroup] = TaggedKeyedGroup(value, tag)
  def apply(value: XAttributeGroup, tag: HostTag): TaggedAttr[XAttributeGroup] = TaggedAttributeGroup(value, tag)
  def apply(value: XTopLevelElement, tag: HostTag): Tagged[XTopLevelElement] = TaggedTopLevelElement(value, tag)
  def apply(value: XLocalElementable, elementFormDefault: XFormChoice, tag: HostTag): TaggedParticle[XLocalElementable] =
    TaggedLocalElement(value, elementFormDefault, tag)
  def apply(value: XAttributable, tag: HostTag): TaggedAttr[XAttributable] = TaggedAttribute(value, tag)
  def apply(value: XAny, tag: HostTag): TaggedParticle[XAny] = TaggedWildCard(value, tag)
  def apply(value: XsTypeSymbol, tag: HostTag): TaggedType[XsTypeSymbol] = TaggedSymbol(value, tag)
  def apply(value: XNoFixedFacet, tag: HostTag): Tagged[XNoFixedFacet] = TaggedEnum(value, tag)
  def apply(value: AttributeSeqParam, tag: HostTag): Tagged[AttributeSeqParam] = TaggedAttributeSeqParam(value, tag)
  def apply(value: AllParam, tag: HostTag): Tagged[AllParam] = TaggedAllParam(value, tag)

  implicit def box(value: XSimpleType)(implicit tag: HostTag) = Tagged(value, tag)
  implicit def box(value: XComplexType)(implicit tag: HostTag) = Tagged(value, tag)
  implicit def box(value: XNamedGroup)(implicit tag: HostTag) = Tagged(value, tag)
  implicit def box(value: XGroupRef)(implicit tag: HostTag) = Tagged(value, tag)
  implicit def box(value: KeyedGroup)(implicit tag: HostTag) = Tagged(value, tag)
  implicit def box(value: XAttributeGroup)(implicit tag: HostTag) = Tagged(value, tag)
  implicit def box(value: XTopLevelElement)(implicit tag: HostTag) = Tagged(value, tag)
  // implicit def box(value: XLocalElementable)(implicit tag: HostTag) = Tagged(value, tag)
  implicit def box(value: XAttributable)(implicit tag: HostTag) = Tagged(value, tag)
  implicit def box(value: XAny)(implicit tag: HostTag) = Tagged(value, tag)
  implicit def box(value: XsTypeSymbol)(implicit tag: HostTag) = Tagged(value, tag)
  implicit def box(value: XNoFixedFacet)(implicit tag: HostTag) = Tagged(value, tag)
  implicit def box(value: AttributeSeqParam)(implicit tag: HostTag) = Tagged(value, tag)
  implicit def box(value: AllParam)(implicit tag: HostTag) = Tagged(value, tag)

  implicit def unbox[A](tagged: Tagged[A]): A = tagged.value

  def toParticleDataRecord(tagged: TaggedParticle[_]): DataRecord[XParticleOption] = tagged match {
    case TaggedLocalElement(value, _, tag) => DataRecord(Some(Defs.XML_SCHEMA_URI.toString), Some("element"), value)
    case TaggedKeyedGroup(value, tag)      => DataRecord(Some(Defs.XML_SCHEMA_URI.toString), Some(value.key.toString), value.value)
    case TaggedGroupRef(value, tag)        => DataRecord(Some(Defs.XML_SCHEMA_URI.toString), Some("group"), value)
    case TaggedWildCard(value, tag)        => DataRecord(Some(Defs.XML_SCHEMA_URI.toString), Some("any"), value)
  }
}

// Tagged Particle
sealed trait TaggedParticle[+A] extends Tagged[A] {}
case class TaggedLocalElement(value: XLocalElementable, elementFormDefault: XFormChoice,
                              tag: HostTag) extends TaggedParticle[XLocalElementable] {}
case class TaggedGroupRef(value: XGroupRef, tag: HostTag) extends TaggedParticle[XGroupRef] {}
case class TaggedKeyedGroup(value: KeyedGroup, tag: HostTag) extends TaggedParticle[KeyedGroup] {}
case class TaggedWildCard(value: XAny, tag: HostTag) extends TaggedParticle[XAny] {}

sealed trait TaggedType[+A] extends Tagged[A] {}
case class TaggedSimpleType(value: XSimpleType, tag: HostTag) extends TaggedType[XSimpleType] {}
case class TaggedComplexType(value: XComplexType, tag: HostTag) extends TaggedType[XComplexType] {}
case class TaggedSymbol(value: XsTypeSymbol, tag: HostTag) extends TaggedType[XsTypeSymbol] {}
object TaggedXsAnyType extends TaggedSymbol(XsAnyType, HostTag(Some(Defs.SCALAXB_URI), SimpleTypeHost, "anyType"))

case class TaggedNamedGroup(value: XNamedGroup, tag: HostTag) extends Tagged[XNamedGroup] {}
case class TaggedAttributeGroup(value: XAttributeGroup, tag: HostTag) extends TaggedAttr[XAttributeGroup] {}
case class TaggedTopLevelElement(value: XTopLevelElement, tag: HostTag) extends Tagged[XTopLevelElement] {}
case class TaggedAttribute(value: XAttributable, tag: HostTag) extends TaggedAttr[XAttributable] {}
case class TaggedAnyAttribute(value: XWildcardable, tag: HostTag) extends TaggedAttr[XWildcardable] {}
case class TaggedEnum(value: XNoFixedFacet, tag: HostTag) extends Tagged[XNoFixedFacet] {}
case class TaggedDataRecordSymbol(value: DataRecordSymbol) extends Tagged[DataRecordSymbol] {
  import Defs._
  val tag = HostTag(Some(SCALAXB_URI), SimpleTypeHost, "DataRecord")
}
case class TaggedAttributeSeqParam(value: AttributeSeqParam, tag: HostTag) extends Tagged[AttributeSeqParam] {}
case class TaggedAllParam(value: AllParam, tag: HostTag) extends Tagged[AllParam] {}

case class DataRecordSymbol(member: Tagged[Any]) extends XsTypeSymbol {
  val name = "DataRecordSymbol(" + member + ")"
}

class SchemaIteration(underlying: Seq[Tagged[_]]) extends scala.collection.IndexedSeqLike[Tagged[_], SchemaIteration] {
  lazy val length: Int = underlying.length
  def apply(index: Int): Tagged[_] = underlying(index)

  override def isEmpty = underlying.isEmpty
  override def toSeq = underlying.toSeq
  override def seq = underlying.seq
  override def iterator = underlying.iterator
  override protected[this] def newBuilder: Builder[Tagged[_], SchemaIteration] = SchemaIteration.newBuilder
}

object SchemaIteration {
  import Defs._

  def apply(schema: XSchema): SchemaIteration = new SchemaIteration(schemaToSeq(schema))
  def fromSeq(seq: Seq[Tagged[_]]): SchemaIteration = new SchemaIteration(seq)

  def newBuilder: Builder[Tagged[_], SchemaIteration] = new ArrayBuffer[Tagged[_]] mapResult fromSeq

  implicit def canBuildFrom: CanBuildFrom[SchemaIteration, Tagged[_], SchemaIteration] =
    new CanBuildFrom[SchemaIteration, Tagged[_], SchemaIteration] {
      def apply(): Builder[Tagged[_], SchemaIteration] = newBuilder
      def apply(from: SchemaIteration): Builder[Tagged[_], SchemaIteration] = newBuilder
    }

  def toThat(decl: XSimpleType, tag: HostTag): Option[Tagged[_]] = Some(Tagged(decl, tag))
  def toThat(decl: XComplexType, tag: HostTag): Option[Tagged[_]] = Some(Tagged(decl, tag))
  def toThat(group: XNamedGroup, tag: HostTag): Option[Tagged[_]] = Some(Tagged(group, tag))
  def toThat(group: XGroupRef, tag: HostTag): Option[Tagged[_]] = Some(Tagged(group, tag))
  def toThat(group: KeyedGroup, tag: HostTag): Option[Tagged[_]] = Some(Tagged(group, tag))
  def toThat(group: XAttributeGroup, tag: HostTag): Option[Tagged[_]] = Some(Tagged(group, tag))
  def toThat(elem: XTopLevelElement, tag: HostTag): Option[Tagged[_]] = Some(Tagged(elem, tag))
  def toThat(elem: XLocalElementable, elementFormDefault: XFormChoice, tag: HostTag): Option[Tagged[_]] =
    Some(Tagged(elem, elementFormDefault, tag))
  def toThat(attr: XAttributable, tag: HostTag): Option[Tagged[_]] = Some(Tagged(attr, tag))

  def schemaToSeq(schema: XSchema): Seq[Tagged[_]] = {
    implicit val s = schema
    val ns = schema.targetNamespace

    // <xs:element ref="xs:simpleType"/>
    // <xs:element ref="xs:complexType"/>
    // <xs:element ref="xs:group"/>
    // <xs:element ref="xs:attributeGroup"/>
    // <xs:element ref="xs:element"/>
    // <xs:element ref="xs:attribute"/>
    // <xs:element ref="xs:notation"/>
    schema.xschemasequence1.toSeq flatMap {
      case XSchemaSequence1(data, _) => data match {
        case DataRecord(_, _, x: XTopLevelSimpleType)  =>
          processSimpleType(x)(HostTag(ns, x))
        case DataRecord(_, _, x: XTopLevelComplexType) =>
          Tagged(x, HostTag(ns, x)).toSeq
        case DataRecord(_, _, x: XNamedGroup)  =>
          processNamedGroup(x)(HostTag(ns, x), s)
        case DataRecord(_, _, x: XNamedAttributeGroup) =>
          processAttributeGroup(x)(HostTag(ns, x))
        case DataRecord(_, _, x: XTopLevelElement)     =>
          processTopLevelElement(x)(HostTag(ns, x), s)
        case DataRecord(_, _, x: XTopLevelAttribute)   =>
          processAttribute(x)(HostTag(ns, x))
        case DataRecord(_, _, x: XNotation)            => Nil
      }
    }
  }

  def processSimpleType(decl: XSimpleType)(implicit tag: HostTag): Seq[Tagged[_]] =
    toThat(decl, tag).toSeq ++
    (decl.arg1 match {
      case DataRecord(_, _, restriction: XRestriction) =>
        restriction.arg1.simpleType map { processSimpleType } getOrElse {Nil}
      case DataRecord(_, _, list: XList) =>
        list.simpleType map { processSimpleType } getOrElse {Nil}
      case DataRecord(_, _, x: XUnion) => Nil
    })

  // all, choice, and sequence are XExplicitGroupable, which are XGroup.
  // <xs:element name="element" type="xs:localElement"/>
  // <xs:element name="group" type="xs:groupRef"/>
  // <xs:element ref="xs:all"/>
  // <xs:element ref="xs:choice"/>
  // <xs:element ref="xs:sequence"/>
  // <xs:element ref="xs:any"/>
  private def processGroupParticle(particleKey: String, particle: XParticleOption)
                                  (implicit tag: HostTag, schema: XSchema): Seq[Tagged[_]] =
    particle match {
      case x: XLocalElementable  => processLocalElement(x)
      case x: XGroupRef          => processGroupRef(x)
      case x: XExplicitGroupable => processGroup(KeyedGroup(particleKey, x))
      case x: XAny               => Nil
    }

  def processGroup(group: KeyedGroup)(implicit tag: HostTag, schema: XSchema): Seq[Tagged[_]] =
    toThat(group, tag).toSeq ++
    (group.value.arg1.toSeq.flatMap {
      case DataRecord(_, Some(particleKey), x: XParticleOption) => processGroupParticle(particleKey, x)
      case _ => Nil
    })

  def processNamedGroup(group: XNamedGroup)(implicit tag: HostTag, schema: XSchema): Seq[Tagged[_]] =
    toThat(group, tag).toSeq ++
    (group.arg1.toSeq.flatMap {
      case DataRecord(_, Some(particleKey), x: XParticleOption) => processGroupParticle(particleKey, x)
      case _ => Nil
    })

  def processGroupRef(ref: XGroupRef)(implicit tag: HostTag, schema: XSchema): Seq[Tagged[_]] =
    toThat(ref, tag).toSeq // ++
    // (resolveNamedGroup(ref.ref.get).arg1.toSeq.flatMap {
    //   case DataRecord(_, Some(particleKey), x: XParticleOption) => processGroupParticle(particleKey, x)
    //   case _ => Nil
    // })

  def processAttributeGroup(group: XAttributeGroup)(implicit tag: HostTag): Seq[Tagged[_]] =
    toThat(group, tag).toSeq ++
    processAttrSeq(group.arg1)

  def processTopLevelElement(elem: XTopLevelElement)(implicit tag: HostTag, schema: XSchema): Seq[Tagged[_]] =
    toThat(elem, tag).toSeq ++
    (elem.xelementoption map { _.value match {
      case x: XLocalComplexType => Tagged(x, tag).toSeq
      case x: XLocalSimpleType  => processSimpleType(x)
    }} getOrElse {Nil})

  private def processLocalElement(elem: XLocalElementable)(implicit tag: HostTag, schema: XSchema): Seq[Tagged[_]] =
    toThat(elem, schema.elementFormDefault, tag).toSeq ++
    (elem.xelementoption map { _.value match {
      case x: XLocalComplexType => Tagged(x, tag).toSeq
      case x: XLocalSimpleType  => processSimpleType(x)
    }} getOrElse {Nil})

  def processAttribute(attr: XAttributable)(implicit tag: HostTag): Seq[Tagged[_]] =
    toThat(attr, tag).toSeq ++
    (attr.simpleType map { processSimpleType } getOrElse {Nil})

  def processAnyAttribute(anyAttribute: XWildcardable)(implicit tag: HostTag): Seq[Tagged[_]] =
    Seq(TaggedAnyAttribute(anyAttribute, tag))

  def processAttrSeq(attrSeq: XAttrDeclsSequence)(implicit tag: HostTag): Seq[Tagged[_]] =
    (attrSeq.xattrdeclsoption1 flatMap {
      case DataRecord(_, _, x: XAttributable)      => processAttribute(x)
      case DataRecord(_, _, x: XAttributeGroupRef) => processAttributeGroup(x)
    }) ++
    (attrSeq.anyAttribute map {processAnyAttribute} getOrElse {Nil})
}

case class ComplexTypeOps(decl: Tagged[XComplexType]) {
  def particles(implicit lookup: Lookup, targetNamespace: Option[URI], scope: NamespaceBinding) =
    ComplexTypeIteration.complexTypeToParticles(decl)

  lazy val primaryCompositor: Option[TaggedParticle[_]] = ComplexTypeIteration.primaryCompositor(decl)
  lazy val primarySequence: Option[TaggedParticle[KeyedGroup]] = ComplexTypeIteration.primarySequence(decl)
  lazy val primaryChoice: Option[TaggedParticle[KeyedGroup]] = ComplexTypeIteration.primaryChoice(decl)
  lazy val primaryAll: Option[TaggedParticle[KeyedGroup]] = ComplexTypeIteration.primaryAll(decl)

  def compositors(implicit lookup: Lookup, targetNamespace: Option[URI], scope: NamespaceBinding) =
    ComplexTypeIteration.complexTypeToCompositors(decl)

  def flattenedCompositors(implicit lookup: Lookup, targetNamespace: Option[URI], scope: NamespaceBinding) =
    ComplexTypeIteration.complexTypeToFlattenedCompositors(decl)

  def flattenedGroups(implicit lookup: Lookup, targetNamespace: Option[URI], scope: NamespaceBinding) =
    ComplexTypeIteration.complexTypeToFlattenedGroups(decl)

  def flattenedAttributes(implicit lookup: Lookup, targetNamespace: Option[URI], scope: NamespaceBinding) =
    ComplexTypeIteration.complexTypeToMergedAttributes(decl)

  def attributeGroups(implicit lookup: Lookup, targetNamespace: Option[URI], scope: NamespaceBinding) =
    ComplexTypeIteration.complexTypeToAttributeGroups(decl)
}

class ComplexTypeIteration(underlying: Seq[Tagged[_]]) extends scala.collection.IndexedSeqLike[Tagged[_], ComplexTypeIteration] {
  lazy val length: Int = seq.length
  def apply(index: Int): Tagged[_] = seq(index)

  override def isEmpty = underlying.isEmpty
  override def toSeq = underlying.toSeq
  override def seq = underlying.seq
  override def iterator = underlying.iterator
  override protected[this] def newBuilder: Builder[Tagged[_], ComplexTypeIteration] = ComplexTypeIteration.newBuilder
}

object ComplexTypeIteration {
  import Defs._

  def apply(decl: Tagged[XComplexType])(implicit schema: XSchema): ComplexTypeIteration =
    new ComplexTypeIteration(complexTypeToSeq(decl))
  def fromSeq(seq: Seq[Tagged[_]]): ComplexTypeIteration = new ComplexTypeIteration(seq)

  def newBuilder: Builder[Tagged[_], ComplexTypeIteration] =
    new ArrayBuffer[Tagged[_]] mapResult fromSeq

  implicit def canBuildFrom: CanBuildFrom[ComplexTypeIteration, Tagged[_], ComplexTypeIteration] =
    new CanBuildFrom[ComplexTypeIteration, Tagged[_], ComplexTypeIteration] {
      def apply(): Builder[Tagged[_], ComplexTypeIteration] = newBuilder
      def apply(from: ComplexTypeIteration): Builder[Tagged[_], ComplexTypeIteration] = newBuilder
    }

  def complexTypeToSeq(decl: Tagged[XComplexType])(implicit schema: XSchema): Seq[Tagged[_]] = {
    implicit val tag = decl.tag

    // <xs:group ref="xs:typeDefParticle"/>
    // <xs:group ref="xs:simpleRestrictionModel"/>
    def processRestriction(restriction: XRestrictionTypable) =
      (restriction.xrestrictiontypableoption map { _ match {
        case DataRecord(_, _, XSimpleRestrictionModelSequence(Some(simpleType), _)) =>
          SchemaIteration.processSimpleType(simpleType)
        // XTypeDefParticleOption is either XGroupRef or XExplicitGroupable
        case DataRecord(_, _, x: XGroupRef)          => SchemaIteration.processGroupRef(x)
        case DataRecord(_, Some(key), x: XExplicitGroupable) => SchemaIteration.processGroup(KeyedGroup(key, x))
        case _ => Nil
      }} getOrElse {Nil}) ++ SchemaIteration.processAttrSeq(restriction.arg2)

    def processExtension(extension: XExtensionTypable) =
      (extension.arg1 map {
        // XTypeDefParticleOption is either XGroupRef or XExplicitGroupable
        case DataRecord(_, _, x: XGroupRef)          => SchemaIteration.processGroupRef(x)
        case DataRecord(_, Some(key), x: XExplicitGroupable) => SchemaIteration.processGroup(KeyedGroup(key, x))
        case _ => Nil
      } getOrElse {Nil}) ++ SchemaIteration.processAttrSeq(extension.arg2)

    Seq(decl) ++
    (decl.value.arg1.value match {
      case XComplexContent(_, DataRecord(_, _, x: XComplexRestrictionType), _, _, _) => processRestriction(x)
      case XComplexContent(_, DataRecord(_, _, x: XExtensionType), _, _, _)          => processExtension(x)
      case XSimpleContent(_, DataRecord(_, _, x: XSimpleRestrictionType), _, _)      => processRestriction(x)
      case XSimpleContent(_, DataRecord(_, _, x: XSimpleExtensionType), _, _)        => processExtension(x)

      // this is an abbreviated form of xs:anyType restriction.
      case XComplexTypeModelSequence1(arg1, arg2) =>
        (arg1 map {
          // XTypeDefParticleOption is either XGroupRef or XExplicitGroupable
          case DataRecord(_, Some(key), x: XGroupRef)          => SchemaIteration.processGroupRef(x)
          case DataRecord(_, Some(key), x: XExplicitGroupable) => SchemaIteration.processGroup(KeyedGroup(key, x))
          case _ => Nil
        } getOrElse {Nil}) ++ SchemaIteration.processAttrSeq(arg2)
    })
  }

  /** particles of the given decl flattened one level.
   * returns list of Tagged[XSimpleType], Tagged[BuiltInSimpleTypeSymbol], Tagged[XElement], Tagged[KeyedGroup],
   * Tagged[XAny].
   * <ul><li>local elements return <code>Tagged[XElement]</code></li>
   * <li>group references return <code>Tagged[KeyedGroup]</code></li>
   * <li>compositors also return <code>Tagged[KeyedGroup]</code></li>
   * <li>xs:any return <code>Tagged[XAny]</code></li>
   * // <li>if the base is a builtin type, it will always return <code>Tagged[BuiltInSimpleTypeSymbol]</code></li>
   * // <li>if the base is a simple type, it will always return <code>Tagged[XSimpleType]</code></li>
   */
  def complexTypeToParticles(decl: Tagged[XComplexType])
    (implicit lookup: Lookup, targetNamespace: Option[URI], scope: NamespaceBinding): Seq[TaggedParticle[_]] = {
    import lookup._
    implicit val tag = decl.tag

    def refToParticles(ref: XGroupRef): Seq[TaggedParticle[_]] = Seq(Tagged(ref, tag))

    def toParticles(group: KeyedGroup): Seq[TaggedParticle[_]] =
      if (group.key == SequenceTag && Occurrence(group).isSingle) group.particles
      else Seq(Tagged(group, tag))

    def processRestriction(restriction: XRestrictionTypable): Seq[TaggedParticle[_]] = {
      val base: QualifiedName = restriction.base
      base match {
        // case BuiltInType(tagged) => Seq(tagged)
        // case SimpleType(tagged)  => Seq(tagged)

        // if base is a complex type, keep the same for inheritance, otherwise it should be anyType
        case ComplexType(tagged) => complexTypeToParticles(tagged)

        // restriction of anyType
        case _ => restriction.xrestrictiontypableoption map { _ match {
          // see http://www.w3.org/TR/xmlschema-1/#Complex_Type_Definitions for details.
          //case DataRecord(_, _, x@XSimpleRestrictionModelSequence(_, _)) =>
          //  x.simpleType map { simpleType => Seq(Tagged(simpleType, tag)) } getOrElse {Nil}

          // XTypeDefParticleOption is either XGroupRef or XExplicitGroupable
          case DataRecord(_, _, x: XGroupRef) => refToParticles(x)
          case DataRecord(_, Some(key), x: XExplicitGroupable) => toParticles(KeyedGroup(key, x))
          case _ => Nil
        }} getOrElse {Nil}
      } // base match
    } // processRestriction

    def processExtension(extension: XExtensionTypable): Seq[TaggedParticle[_]] =  {
      val base: QualifiedName = extension.base
      base match {
        // case BuiltInType(tagged) => Seq(tagged)
        // case SimpleType(tagged)  => Seq(tagged)
        case ComplexType(tagged) =>
          extension.arg1 map {
            // XTypeDefParticleOption is either XGroupRef or XExplicitGroupable
            case DataRecord(_, _, x: XGroupRef)          =>
              complexTypeToParticles(tagged) ++ refToParticles(x)
            case DataRecord(_, Some(key), x: XExplicitGroupable) =>
              complexTypeToParticles(tagged) ++ toParticles(KeyedGroup(key, x))
            case _ => complexTypeToParticles(tagged)
          } getOrElse { complexTypeToParticles(tagged) }

        // extension of anyType.
        case _ =>
          extension.arg1 map {
            // XTypeDefParticleOption is either XGroupRef or XExplicitGroupable
            case DataRecord(_, _, x: XGroupRef)                  => refToParticles(x)
            case DataRecord(_, Some(key), x: XExplicitGroupable) => toParticles(KeyedGroup(key, x))
            case _ => Nil
          } getOrElse {Nil}
      } // base match
    } // processExtension

    decl.value.arg1.value match {
      case XComplexContent(_, DataRecord(_, _, x: XComplexRestrictionType), _, _, _) => processRestriction(x)
      case XComplexContent(_, DataRecord(_, _, x: XExtensionType), _, _, _)          => processExtension(x)
      case XSimpleContent(_, DataRecord(_, _, x: XSimpleRestrictionType), _, _)      => processRestriction(x)
      case XSimpleContent(_, DataRecord(_, _, x: XSimpleExtensionType), _, _)        => processExtension(x)

      // this is an abbreviated form of xs:anyType restriction.
      case XComplexTypeModelSequence1(arg1, arg2) =>
        arg1 map {
          // XTypeDefParticleOption is either XGroupRef or XExplicitGroupable
          case DataRecord(_, _, x: XGroupRef)          => refToParticles(x)
          case DataRecord(_, Some(key), x: XExplicitGroupable) => toParticles(KeyedGroup(key, x))
          case _ => Nil
        } getOrElse {Nil}
    }
  }

  def complexTypeToFlattenedGroups(decl: Tagged[XComplexType])
        (implicit lookup: Lookup, targetNamespace: Option[URI], scope: NamespaceBinding): Seq[TaggedParticle[XGroupRef]] =
    complexTypeToFlattenedCompositors(decl) collect {
      case x: TaggedGroupRef => x
    }

  def complexTypeToFlattenedCompositors(decl: Tagged[XComplexType])
      (implicit lookup: Lookup, targetNamespace: Option[URI], scope: NamespaceBinding): Seq[TaggedParticle[_]] = {
    import lookup._
    def extract(model: Option[DataRecord[Any]]) = model match {
      // XTypeDefParticleOption is either XGroupRef or XExplicitGroupable
      case Some(DataRecord(_, _, x: XGroupRef))          =>
        implicit val tag = decl.tag
        val compositor = Tagged(resolveNamedGroup(x.ref.get), decl.tag)
        val tagged = Tagged(x, decl.tag)
        Seq(tagged) ++ (compositor.particles collect  {
          case Compositor(c) => c
        })
      case Some(DataRecord(_, Some(key), x: XExplicitGroupable)) =>
        implicit val tag = decl.tag
        val compositor = Tagged(KeyedGroup(key, x), decl.tag)
        Seq(compositor) ++ (compositor.particles collect  {
          case Compositor(c) => c
        })
      case _ => Nil
    }

    def qnameCompositors(base: QualifiedName): Seq[TaggedParticle[_]] = base match {
      case ComplexType(tagged) => complexTypeToFlattenedCompositors(tagged)
      case _ => Nil
    }

    decl.value.arg1.value match {
      case XComplexContent(_, DataRecord(_, _, x: XComplexRestrictionType), _, _, _) =>
        qnameCompositors(x.base) ++ extract(x.xrestrictiontypableoption)
      case XComplexContent(_, DataRecord(_, _, x: XExtensionType), _, _, _)          =>
        qnameCompositors(x.base) ++ extract(x.arg1)
      case XSimpleContent(_, _, _, _)                                                => Nil
      // this is an abbreviated form of xs:anyType restriction.
      case XComplexTypeModelSequence1(arg1, arg2)                                    => extract(arg1)
    }
  }

  def primarySequence(decl: Tagged[XComplexType]): Option[TaggedParticle[KeyedGroup]] =
    primaryCompositor(decl) flatMap { _ match {
      case x@TaggedKeyedGroup(g, tag) if g.key == SequenceTag => Some(x)
      case _ => None
    }}

  def primaryChoice(decl: Tagged[XComplexType]): Option[TaggedParticle[KeyedGroup]] =
      primaryCompositor(decl) flatMap { _ match {
        case x@TaggedKeyedGroup(g, tag) if g.key == ChoiceTag => Some(x)
        case _ => None
      }}

  def primaryAll(decl: Tagged[XComplexType]): Option[TaggedParticle[KeyedGroup]] =
      primaryCompositor(decl) flatMap { _ match {
        case x@TaggedKeyedGroup(g, tag) if g.key == AllTag => Some(x)
        case _ => None
      }}

  def primaryCompositor(decl: Tagged[XComplexType]): Option[TaggedParticle[_]] = {
    def extract(model: Option[DataRecord[Any]]) = model match {
      // XTypeDefParticleOption is either XGroupRef or XExplicitGroupable
      case Some(DataRecord(_, Some(key), x: XGroupRef))          => Some(Tagged(x, decl.tag))
      case Some(DataRecord(_, Some(key), x: XExplicitGroupable)) => Some(Tagged(KeyedGroup(key, x), decl.tag))
      case _ => None
    }

    decl.value.arg1.value match {
      case XComplexContent(_, DataRecord(_, _, x: XComplexRestrictionType), _, _, _) =>
        extract(x.xrestrictiontypableoption)
      case XComplexContent(_, DataRecord(_, _, x: XExtensionType), _, _, _)          =>
        extract(x.arg1)

      case XSimpleContent(_, _, _, _)                                                => None
      // this is an abbreviated form of xs:anyType restriction.
      case XComplexTypeModelSequence1(arg1, arg2)                                    => extract(arg1)
    }
  }

  def complexTypeToCompositors(decl: Tagged[XComplexType])
                      (implicit lookup: Lookup,
                       targetNamespace: Option[URI], scope: NamespaceBinding): Seq[TaggedParticle[KeyedGroup]] =
    primarySequence(decl).toSeq ++
    decl.particles collect {
      case Compositor(compositor) => compositor
    }

  def complexTypeToAttributeGroups(decl: Tagged[XComplexType])
                      (implicit lookup: Lookup,
                       targetNamespace: Option[URI], scope: NamespaceBinding): Seq[Tagged[_]] = {
    implicit val s = lookup.schema.unbound
    import lookup._
    (decl collect  {
      case x: TaggedAttributeGroup => x.ref map { resolveAttributeGroup(_) } getOrElse x
    }).toSeq
  }

  /** attributes of the given decl flattened one level.
   * returns list of Tagged[XAttributable], Tagged[XAttributeGroup], Tagged[XWildCardable].
   */
  def complexTypeToMergedAttributes(decl: Tagged[XComplexType])
                      (implicit lookup: Lookup,
                       targetNamespace: Option[URI], scope: NamespaceBinding): Seq[Tagged[_]] = {
    import lookup._
    implicit val tag = decl.tag

    def qnameAttributes(base: QualifiedName) = base match {
      case ComplexType(tagged) => complexTypeToMergedAttributes(tagged)
      case _ => Nil
    }
    
    def isSameAttribute(lhs: Tagged[_], rhs: Tagged[_]): Boolean = {
      (lhs, rhs) match {
        case (l: TaggedAnyAttribute, r: TaggedAnyAttribute) => true
        case (l: TaggedAttribute, r: TaggedAttribute) =>
          QualifiedName(decl.tag.namespace, l.value.name, l.value.ref) ==
          QualifiedName(decl.tag.namespace, r.value.name, r.value.ref)
        case (l: TaggedAttributeGroup, r: TaggedAttributeGroup) =>
          QualifiedName(decl.tag.namespace, l.value.name, l.value.ref) ==
          QualifiedName(decl.tag.namespace, r.value.name, r.value.ref)
        case _ => false
      }
    }

    // since OO's hierarchy does not allow base members to be omitted,
    // child overrides needs to be implemented some other way.
    @tailrec def mergeAttributeSeqs(base: Seq[Tagged[_]], children: Seq[Tagged[_]]): Seq[Tagged[_]] = {
      def mergeAttribute(base: Seq[Tagged[_]], child: Tagged[_]): Seq[Tagged[_]] =
        if (base exists { x => isSameAttribute(x, child) }) base
        else base :+ child

      children match {
        case x :: xs => mergeAttributeSeqs(mergeAttribute(base, x), xs)
        case Nil => base
      }
    }

    def processRestriction(restriction: XRestrictionTypable) =
      mergeAttributeSeqs(qnameAttributes(restriction.base),
        flattenAttrSeq(restriction.arg2))

    def processExtension(extension: XExtensionTypable) =
      mergeAttributeSeqs(qnameAttributes(extension.base),
        flattenAttrSeq(extension.arg2))

    // move anyAttribute to the end.
    def reorderAttributes(xs: Seq[Tagged[_]]): Seq[Tagged[_]] = {
      val (l, r) = xs partition {
        case x: TaggedAnyAttribute => true
        case _ => false
      }
      r ++ l
    }

    // Resolve references as walking through the attributes.
    def flattenAttrSeq(attrSeq: XAttrDeclsSequence)(implicit tag: HostTag): Seq[Tagged[_]] =
      (attrSeq.xattrdeclsoption1 flatMap {
        case DataRecord(_, _, x: XAttributable)      =>
          x.ref map { ref => Seq(resolveAttribute(ref))
          } getOrElse { Seq(Tagged(x, tag)) }
        case DataRecord(_, _, x: XAttributeGroupRef) =>
          x.ref map { ref => flattenAttrSeq(resolveAttributeGroup(ref).value.arg1)
          } getOrElse { flattenAttrSeq(x.arg1) }
      }) ++
      (attrSeq.anyAttribute map {SchemaIteration.processAnyAttribute} getOrElse {Nil})

    val retval = decl.value.arg1.value match {
      case XComplexContent(_, DataRecord(_, _, x: XComplexRestrictionType), _, _, _) => processRestriction(x)
      case XComplexContent(_, DataRecord(_, _, x: XExtensionType), _, _, _)          => processExtension(x)
      case XSimpleContent(_, DataRecord(_, _, x: XSimpleRestrictionType), _, _)      => processRestriction(x)
      case XSimpleContent(_, DataRecord(_, _, x: XSimpleExtensionType), _, _)        => processExtension(x)

      // this is an abbreviated form of xs:anyType restriction.
      case XComplexTypeModelSequence1(arg1, arg2) =>
        flattenAttrSeq(arg2)
    }
    reorderAttributes(retval)
  }
}

object Compositor {
  import Defs._
  def unapply(value: Tagged[_]): Option[TaggedKeyedGroup] = value match {
    case x: TaggedKeyedGroup => Some(x)
    case _ => None
  }
}

class ElementOps(val tagged: Tagged[XElement]) {
  def resolve(implicit lookup: Lookup): Tagged[XElement] = {
    import lookup._
    tagged.value.ref match {
      case Some(Element(x)) => x
      case _ => tagged
    }
  }

  def typeStructure(implicit lookup: Lookup): TaggedType[_] = {
    import lookup._
    val elem = resolve.value

    // http://www.w3.org/TR/xmlschema-1/#declare-element
    // An <element> with no referenced or included type definition will correspond to an element declaration which
    // has the same type definition as the head of its substitution group if it identifies one, otherwise the
    // **ur-type definition**.
    val typeValue = elem.typeValue map { resolveType(_) }
    val localType = elem.xelementoption map { _ match {
      case DataRecord(_, _, x: XLocalSimpleType)  => Tagged(x, tagged.tag)
      case DataRecord(_, _, x: XLocalComplexType) => Tagged(x, tagged.tag)
    }}
    typeValue getOrElse {
      localType getOrElse { TaggedXsAnyType }
    }
  }

  def qualified: Boolean = tagged match {
    case TaggedTopLevelElement(_, _) => true
    case elem: TaggedLocalElement =>
      elem.value.form map {_ == XQualified} getOrElse {elem.elementFormDefault == XQualified}
  }

  // @todo implement this
  def isSubstitutionGroup: Boolean = false
}

class AttributeGroupOps(val tagged: Tagged[XAttributeGroup]) {
  def flattenedAttributes: Seq[Tagged[_]] = SchemaIteration.processAttrSeq(tagged.value.arg1)(tagged.tag)
}

