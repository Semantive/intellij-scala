package org.jetbrains.plugins.scala
package caches


import lang.psi.types.result.TypeResult
import com.intellij.psi._
import lang.psi.api.toplevel.typedef.{ScObject, ScTypeDefinition}
import lang.psi.api.expr.ScExpression.ExpressionTypeResult
import lang.psi.api.base.types.ScTypeElement
import com.intellij.openapi.util.{Computable, RecursionManager, RecursionGuard, Key}
import lang.psi.api.expr.{MethodInvocation, ScExpression}
import lang.psi.api.toplevel.imports.usages.ImportUsed
import collection.mutable.ArrayBuffer
import org.jetbrains.plugins.scala.lang.resolve.{ResolvableStableCodeReferenceElement, ResolvableReferenceExpression, ScalaResolveResult}
import lang.psi.types.{ScUndefinedSubstitutor, ScSubstitutor, ScType}
import lang.psi.api.statements.params.{ScTypeParamClause, ScParameterClause}
import com.intellij.util.containers.ConcurrentHashMap
import util.{PsiTreeUtil, CachedValuesManager, CachedValueProvider, CachedValue}
import lang.psi.api.statements.ScFunction
import scala.util.control.ControlThrowable
import lang.psi.impl.ScPackageImpl
import org.jetbrains.plugins.scala.lang.psi.implicits.ScImplicitlyConvertible
import org.jetbrains.plugins.scala.lang.psi.implicits.ScImplicitlyConvertible.ImplicitResolveResult
import org.jetbrains.plugins.scala.lang.psi.api.base.ScStableCodeReferenceElement

/**
 * User: Alexander Podkhalyuzin
 * Date: 08.06.2009
 */
object CachesUtil {
  //keys for cachedValue

  val IMPLICIT_SIMPLE_MAP_KEY: Key[CachedValue[ArrayBuffer[ScImplicitlyConvertible.ImplicitMapResult]]] =
    Key.create("implicit.simple.map.key")

  val OBJECT_SYNTHETIC_MEMBERS_KEY: Key[CachedValue[Seq[PsiMethod]]] = Key.create("object.synthetic.members.key")
  val SYNTHETIC_MEMBERS_KEY: Key[CachedValue[Seq[PsiMethod]]] = Key.create("stynthetic.members.key")
  val DESUGARIZED_EXPR_KEY: Key[CachedValue[Option[ScExpression]]] = Key.create("desugarized.expr.key")
  val STRING_CONTEXT_EXPANDED_EXPR_KEY: Key[CachedValue[Option[ScExpression]]] = Key.create("string.context.expanded.expr.key")

  val IS_FUNCTION_INHERITOR_KEY: Key[CachedValue[Boolean]] = Key.create("is.function1.inheritor.key")
  val CONSTRUCTOR_TYPE_PARAMETERS_KEY: Key[CachedValue[Option[ScTypeParamClause]]] =
    Key.create("constructor.type.parameters.key")
  val REF_EXPRESSION_NON_VALUE_RESOLVE_KEY: Key[CachedValue[Array[ResolveResult]]] =
    Key.create("ref.expression.non.value.resolve.key")
  val IS_SCRIPT_FILE_KEY: Key[CachedValue[Boolean]] = Key.create("is.script.file.key")
  val FUNCTION_EFFECTIVE_PARAMETER_CLAUSE_KEY: Key[CachedValue[Seq[ScParameterClause]]] =
    Key.create("function.effective.parameter.clause.key")
  val TYPE_WITHOUT_IMPLICITS_WITHOUT_UNDERSCORE: Key[CachedValue[TypeResult[ScType]]] =
    Key.create("type.without.implicits.without.underscore.key")
  val ALIASED_KEY: Key[CachedValue[TypeResult[ScType]]] = Key.create("alised.type.key")
  val SCRIPT_KEY: Key[CachedValue[java.lang.Boolean]] = Key.create("is.script.key")
  val SCALA_PREDEFINED_KEY: Key[CachedValue[java.lang.Boolean]] = Key.create("scala.predefined.key")
  val EXPR_TYPE_KEY: Key[CachedValue[ScType]] = Key.create("expr.type.key")
  val TYPE_KEY: Key[CachedValue[TypeResult[ScType]]] = Key.create("type.element.type.key")
  val PSI_RETURN_TYPE_KEY: Key[CachedValue[PsiType]] = Key.create("psi.return.type.key")
  val SUPER_TYPES_KEY: Key[CachedValue[List[ScType]]] = Key.create("super.types.key")
  val EXTENDS_BLOCK_SUPER_TYPES_KEY: Key[CachedValue[List[ScType]]] = Key.create("extends.block.super.types.key")
  val EXTENDS_BLOCK_SUPERS_KEY: Key[CachedValue[Seq[PsiClass]]] = Key.create("extends.block.supers.key")

  val FAKE_CLASS_COMPANION: Key[CachedValue[Option[ScObject]]] = Key.create("fake.class.companion.key")
  val EFFECTIVE_PARAMETER_CLAUSE: Key[CachedValue[Seq[ScParameterClause]]] =
    Key.create("effective.parameter.clause.key")
  val PATTERN_EXPECTED_TYPE: Key[CachedValue[Option[ScType]]] = Key.create("pattern.expected.type.key")

  //keys for getUserData
  val EXPRESSION_TYPING_KEY: Key[java.lang.Boolean] = Key.create("expression.typing.key")
  val IMPLICIT_TYPE: Key[ScType] = Key.create("implicit.type")
  val IMPLICIT_FUNCTION: Key[PsiNamedElement] = Key.create("implicit.function")
  val NAMED_PARAM_KEY: Key[java.lang.Boolean] = Key.create("named.key")
  val PACKAGE_OBJECT_KEY: Key[(ScTypeDefinition, java.lang.Long)] = Key.create("package.object.key")

  def get[Dom <: PsiElement, T](e: Dom, key: Key[CachedValue[T]], provider: => CachedValueProvider[T]): T = {
    var computed: CachedValue[T] = e.getUserData(key)
    if (computed == null) {
      val manager = CachedValuesManager.getManager(e.getProject)
      computed = manager.createCachedValue(provider, false)
      e.putUserData(key, computed)
    }
    computed.getValue
  }

  trait MyProviderTrait[Dom, T] extends CachedValueProvider[T] {
    private[CachesUtil] def getDependencyItem: Option[Object]
  }

  class MyProvider[Dom, T](e: Dom, builder: Dom => T)(dependencyItem: Object) extends MyProviderTrait[Dom, T] {
    private[CachesUtil] def getDependencyItem: Option[Object] = Some(dependencyItem)

    def compute() = new CachedValueProvider.Result(builder(e), dependencyItem)
  }

  class MyOptionalProvider[Dom, T](e: Dom, builder: Dom => T)(dependencyItem: Option[Object]) extends MyProviderTrait[Dom, T] {
    private[CachesUtil] def getDependencyItem: Option[Object] = dependencyItem

    def compute() = {
      dependencyItem match {
        case Some(depItem) =>
          new CachedValueProvider.Result(builder(e), depItem)
        case _ => new CachedValueProvider.Result(builder(e))
      }
    }
  }

  private val guards: ConcurrentHashMap[String, RecursionGuard] = new ConcurrentHashMap()
  def getRecursionGuard(id: String): RecursionGuard = {
    val guard = guards.get(id)
    if (guard == null) {
      val result = RecursionManager.createGuard(id)
      guards.put(id, result)
      result
    } else guard
  }

  case class ProbablyRecursionException[Dom <: PsiElement, Data, T](elem: Dom,
                                                                            data: Data,
                                                                            key: Key[T],
                                                                            set: Set[ScFunction]) extends ControlThrowable
}