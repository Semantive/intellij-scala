package org.jetbrains.plugins.scala.codeInsight.intention.controlflow

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.editor.Editor
import com.intellij.psi.{PsiManager, PsiDocumentManager, PsiElement}
import org.jetbrains.plugins.scala.lang.psi.api.expr._
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.extensions._
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiElementFactory
import org.jetbrains.plugins.scala.lang.psi.impl.expr.ScBlockImpl
import com.intellij.psi.codeStyle.CodeStyleManager

/**
 * @author Ksenia.Sautina
 * @since 6/8/12
 */

object RemoveRedundantElseIntention {
  def familyName = "Remove redundant Else"
}

class RemoveRedundantElseIntention extends PsiElementBaseIntentionAction {
  def getFamilyName = RemoveRedundantElseIntention.familyName

  override def getText: String = "Remove redundant 'else'"

  def isAvailable(project: Project, editor: Editor, element: PsiElement): Boolean = {
    val ifStmt: ScIfStmt = PsiTreeUtil.getParentOfType(element, classOf[ScIfStmt], false)
    if (ifStmt == null) return false

    val thenBranch = ifStmt.thenBranch.getOrElse(null)
    val elseBranch = ifStmt.elseBranch.getOrElse(null)
    val condition = ifStmt.condition.getOrElse(null)
    if (thenBranch == null || elseBranch == null || condition == null) return false

    val offset = editor.getCaretModel.getOffset
    if (!(thenBranch.getTextRange.getEndOffset <= offset && offset <= elseBranch.getTextRange.getStartOffset))
      return false

    thenBranch match {
      case tb: ScBlockExpr =>
        val lastExpr = tb.lastExpr.getOrElse(null)
        if (lastExpr == null) return false
        if (lastExpr.isInstanceOf[ScReturnStmt]) return true
        if (lastExpr.isInstanceOf[ScThrowStmt]) return true
        false
      case e: ScExpression =>
        if (e.isInstanceOf[ScReturnStmt]) return true
        if (e.isInstanceOf[ScThrowStmt]) return true
        false
      case _ => false
    }
  }

  override def invoke(project: Project, editor: Editor, element: PsiElement) {
    val manager: PsiManager = PsiManager.getInstance(project)
    val ifStmt: ScIfStmt = PsiTreeUtil.getParentOfType(element, classOf[ScIfStmt], false)
    if (ifStmt == null || !ifStmt.isValid) return

    val thenBranch = ifStmt.thenBranch.getOrElse(null)
    if (thenBranch == null) return
    val elseKeyWord = thenBranch.getNextSiblingNotWhitespaceComment

    val elseBranch = ifStmt.elseBranch.getOrElse(null)
    if (elseBranch == null) return

    val exprText = elseBranch match {
      case eb: ScBlockExpr => eb.getText.trim.drop(1).dropRight(1)
      case _ => elseBranch.getText.trim
    }

    val newExpr: ScBlockImpl = ScalaPsiElementFactory.createBlockExpressionWithoutBracesFromText(exprText, manager)

    inWriteAction {
      elseKeyWord.delete()
      elseBranch.delete()
      ifStmt.getParent.addAfter(newExpr, ifStmt)
      ifStmt.getParent.addAfter(ScalaPsiElementFactory.createNewLine(manager), ifStmt)
      PsiDocumentManager.getInstance(project).commitDocument(editor.getDocument)
    }
  }
}
