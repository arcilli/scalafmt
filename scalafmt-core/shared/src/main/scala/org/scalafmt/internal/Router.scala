package org.scalafmt.internal

import org.scalafmt.Error.UnexpectedTree
import org.scalafmt.config.{Align, BinPack}
import org.scalafmt.config.{ImportSelectors, Newlines, ScalafmtConfig, Spaces}
import org.scalafmt.internal.ExpiresOn.{After, Before}
import org.scalafmt.internal.Length.{Num, StateColumn}
import org.scalafmt.internal.Policy.NoPolicy
import org.scalafmt.sysops.FileOps
import org.scalafmt.util._
import org.scalameta.FileLine

import scala.annotation.tailrec
import scala.language.implicitConversions
import scala.meta.Term.ApplyUsing
import scala.meta.classifiers.Classifier
import scala.meta.tokens.{Token => T}
import scala.meta.{
  Case,
  CaseTree,
  Defn,
  Enumerator,
  ImportExportStat,
  Importer,
  Init,
  Lit,
  Mod,
  Pat,
  Pkg,
  Template,
  Term,
  Tree,
  Type,
  TypeCase
}

object Constants {
  val ShouldBeNewline = 100000
  val ShouldBeSingleLine = 30
  val BinPackAssignmentPenalty = 10
  val SparkColonNewline = 10
  val BracketPenalty = 20
  val ExceedColumnPenalty = 1000
  // Breaking a line like s"aaaaaaa${111111 + 22222}" should be last resort.
  val BreakSingleLineInterpolatedString = 10 * ExceedColumnPenalty
}

/** Assigns splits to format tokens.
  *
  * NOTE(olafurpg). The pattern match in this file has gotten out of hand. It's
  * difficult even for myself to keep track of what's going on in some cases,
  * especially around applications and lambdas. I'm hoping to sunset this file
  * along with BestFirstSearch in favor of
  * https://github.com/scalameta/scalafmt/issues/917
  */
class Router(formatOps: FormatOps) {

  import Constants._
  import LoggerOps._
  import PolicyOps._
  import TokenOps._
  import TreeOps._
  import FormatOps._
  import formatOps._

  import tokens.{
    matching,
    matchingOpt,
    prev,
    next,
    tokenBefore,
    getLastNonTrivial,
    prevNonComment,
    prevNonCommentBefore,
    nextNonComment,
    prevNonCommentSameLine,
    nextNonCommentSameLine
  }

  private def getSplitsImpl(implicit formatToken: FormatToken): Seq[Split] = {
    implicit val style = styleMap.at(formatToken)
    val leftOwner = formatToken.meta.leftOwner
    val rightOwner = formatToken.meta.rightOwner
    val newlines = formatToken.newlinesBetween

    formatToken match {
      // between sources (EOF -> @ -> BOF)
      case FormatToken(_: T.EOF, _, _) => Seq(Split(Newline, 0))
      case ft @ FormatToken(_, _: T.BOF, _) =>
        Seq(Split(NoSplit.orNL(next(ft).right.is[T.EOF]), 0))
      case FormatToken(_: T.BOF, right, _) =>
        val policy = right match {
          case T.Ident(name) // shebang in .sc files
              if FileOps.isAmmonite(filename) && name.startsWith("#!") =>
            val nl = findFirst(next(formatToken), Int.MaxValue) { x =>
              x.hasBreak || x.right.is[T.EOF]
            }
            nl.fold(Policy.noPolicy) { ft =>
              Policy.on(ft.left) { case Decision(t, _) =>
                Seq(Split(Space(t.between.nonEmpty), 0))
              }
            }
          case _ => Policy.NoPolicy
        }
        Seq(
          Split(NoSplit, 0).withPolicy(policy)
        )
      case FormatToken(_, _: T.EOF, _) =>
        Seq(
          Split(Newline, 0) // End files with trailing newline
        )
      case FormatToken(start: T.Interpolation.Start, _, _) =>
        val end = matching(start)
        val okNewlines =
          style.newlines.inInterpolation.ne(Newlines.InInterpolation.avoid) &&
            isTripleQuote(formatToken.meta.left.text)
        def policy = PenalizeAllNewlines(end, BreakSingleLineInterpolatedString)
        val split = Split(NoSplit, 0).withPolicy(policy, okNewlines)
        val alignIndents = if (style.align.stripMargin) {
          findInterpolate(leftOwner).flatMap { ti =>
            getStripMarginChar(ti).map { pipe =>
              val startsWithPipe = ti.parts.headOption match {
                case Some(Lit.String(x)) => x.headOption.contains(pipe)
                case _ => false
              }
              Seq(
                Indent(StateColumn, end, After),
                // -1 because of margin characters |
                Indent(if (startsWithPipe) -1 else -2, end, After)
              )
            }
          }
        } else None
        val indents =
          alignIndents.getOrElse(Seq(Indent(style.indent.main, end, After)))
        Seq(split.withIndents(indents))
      // Interpolation
      case FormatToken(
            _: T.Interpolation.Id | _: T.Interpolation.Part |
            _: T.Interpolation.Start | _: T.Interpolation.SpliceStart,
            _,
            _
          ) =>
        Seq(
          Split(NoSplit, 0)
        )
      case FormatToken(
            _,
            T.Interpolation.Part(_) | T.Interpolation.End() |
            T.Interpolation.SpliceEnd(),
            _
          ) =>
        Seq(
          Split(NoSplit, 0)
        )
      case FormatToken(T.LeftBrace(), T.RightBrace(), _) =>
        Seq(
          Split(NoSplit, 0)
        )
      // Import
      case FormatToken(_: T.Dot, _, _)
          if existsParentOfType[ImportExportStat](rightOwner) =>
        Seq(Split(NoSplit, 0))
      // Import left brace
      case FormatToken(open: T.LeftBrace, _, _)
          if existsParentOfType[ImportExportStat](leftOwner) =>
        val close = matching(open)
        val policy = SingleLineBlock(
          close,
          okSLC = style.importSelectors eq ImportSelectors.singleLine
        )
        val newlineBeforeClosingCurly = decideNewlinesOnlyBeforeClose(close)

        val mustDangleForTrailingCommas = getMustDangleForTrailingCommas(close)
        val mustUseNL = newlines != 0 &&
          tokens.isRightCommentThenBreak(formatToken)
        val newlinePolicy = style.importSelectors match {
          case ImportSelectors.singleLine if mustUseNL =>
            policy
          case ImportSelectors.singleLine if !mustDangleForTrailingCommas =>
            NoPolicy
          case ImportSelectors.binPack =>
            newlineBeforeClosingCurly
          case _ =>
            newlineBeforeClosingCurly & splitOneArgOneLine(close, leftOwner)
        }

        Seq(
          Split(Space(style.spaces.inImportCurlyBraces), 0)
            .notIf(mustUseNL || mustDangleForTrailingCommas)
            .withPolicy(policy),
          Split(Newline, 1)
            .onlyIf(newlinePolicy ne NoPolicy)
            .withPolicy(newlinePolicy)
            .withIndent(style.indent.main, close, Before)
        )
      case FormatToken(_, _: T.RightBrace, _)
          if existsParentOfType[ImportExportStat](rightOwner) =>
        Seq(Split(Space(style.spaces.inImportCurlyBraces), 0))

      // Interpolated string left brace
      case FormatToken(open @ T.LeftBrace(), _, _)
          if prev(formatToken).left.is[T.Interpolation.SpliceStart] =>
        val close = matching(open)
        val alignIndents =
          if (style.align.inInterpolation) Some {
            Seq(
              Indent(Length.StateColumn, close, ExpiresOn.After),
              Indent(Length.Num(style.indent.main), close, ExpiresOn.Before),
              Indent(Length.Num(-1), close, ExpiresOn.After)
            )
          }
          else None
        def spaceSplit(implicit fileLine: FileLine) =
          Split(Space(style.spaces.inInterpolatedStringCurlyBraces), 0)
            .withIndents(alignIndents.getOrElse(Nil))
        def newlineSplit(cost: Int)(implicit fileLine: FileLine) = {
          def mainIndents = Seq(
            Indent(style.indent.main, close, ExpiresOn.Before)
          )
          Split(Newline, cost)
            .withIndents(alignIndents.getOrElse(mainIndents))
            .withPolicy(decideNewlinesOnlyBeforeClose(close))
        }
        def findArg = findInterpolateArgAfter(open.end, leftOwner)
        style.newlines.inInterpolation match {
          case Newlines.InInterpolation.avoid => Seq(spaceSplit)
          case _ if style.newlines.keepBreak(newlines) =>
            Seq(newlineSplit(0))
          case Newlines.InInterpolation.allow
              if !dialect.allowSignificantIndentation ||
                !findArg.exists(_.is[Term.If]) =>
            Seq(spaceSplit)
          case _ =>
            /* sequence of tokens:
             * - 0 RBrace
             * - 1 Interpolation.SpliceEnd (empty)
             * - 2 Interpolation.Part (next string)
             * - 3 Interpolation.End (quotes) or Interpolation.SliceStart/LBrace (${)
             */
            val afterClose = tokens(close, 3)
            val lastPart = afterClose.left.is[T.Interpolation.End]
            val slbEnd = if (lastPart) afterClose.left else afterClose.right
            Seq(
              spaceSplit.withSingleLine(slbEnd),
              newlineSplit(1)
            )
        }

      case FormatToken(_, _: T.RightBrace, _)
          if rightOwner.is[SomeInterpolate] =>
        Seq(Split(Space(style.spaces.inInterpolatedStringCurlyBraces), 0))

      // optional braces: block follows
      case FormatToken(
            _: T.Equals | _: T.Colon | _: T.KwWith | _: T.RightParen |
            _: T.KwReturn | _: T.ContextArrow | _: T.LeftArrow |
            _: T.RightArrow | _: T.KwMatch | _: T.KwThen | _: T.KwElse |
            _: T.KwThrow | _: T.KwTry | _: T.KwCatch | _: T.KwFinally |
            _: T.KwFor | _: T.KwDo | _: T.KwWhile | _: T.KwYield | _: T.KwIf,
            _,
            OptionalBraces(splits)
          ) if dialect.allowSignificantIndentation =>
        splits

      // { ... } Blocks
      case tok @ FormatToken(open @ T.LeftBrace(), right, _) =>
        val close = matching(open)
        val closeFT = tokens(close)
        val newlineBeforeClosingCurly = decideNewlinesOnlyBeforeClose(close)
        val selfAnnotationLast: Option[T] = leftOwner match {
          // Self type: trait foo { self => ... }
          case t: Template => t.self.tokens.lastOption
          case _ => None
        }
        val isSelfAnnotationNL = selfAnnotationLast.nonEmpty &&
          style.optIn.selfAnnotationNewline &&
          (formatToken.hasBreak || style.newlines.sourceIgnored)
        val rightIsComment = right.is[T.Comment]
        val nl: Modification =
          if (rightIsComment && tok.noBreak) Space
          else {
            val double = tok.hasBlankLine ||
              !isSelfAnnotationNL &&
              rightIsComment && blankLineBeforeDocstring(tok)
            NewlineT(double)
          }

        // lambdaNLOnly: None for single line only
        val (lambdaExpire, lambdaArrow, lambdaIndent, lambdaNLOnly) =
          startsStatement(right) match {
            case Some(owner: Term.FunctionTerm) =>
              val arrow = getFuncArrow(lastLambda(owner))
              val expire = arrow.getOrElse(tokens.getLast(owner))
              val nlOnly =
                if (style.newlines.alwaysBeforeCurlyLambdaParams) Some(true)
                else if (
                  style.newlines.beforeCurlyLambdaParams eq
                    Newlines.BeforeCurlyLambdaParams.multiline
                ) None
                else Some(false)
              (expire, arrow.map(_.left), 0, nlOnly)
            case Some(t: Case) if t.cond.isEmpty && (leftOwner match {
                  case Term.PartialFunction(List(`t`)) => true
                  case x @ Term.Match(_, List(`t`)) => getMatchDot(x).isDefined
                  case _ => false
                }) =>
              val arrow = getCaseArrow(t)
              val nlOnly =
                if (style.newlines.alwaysBeforeCurlyLambdaParams) Some(true)
                else if (
                  style.newlines.beforeCurlyLambdaParams ne
                    Newlines.BeforeCurlyLambdaParams.never
                ) None
                else Some(false)
              (arrow, Some(arrow.left), 0, nlOnly)
            case _ =>
              selfAnnotationLast match {
                case Some(anno) =>
                  val arrow = leftOwner.tokens.find(_.is[T.RightArrow])
                  val expire = arrow.getOrElse(anno)
                  val indent = style.indent.main
                  (tokens(expire), arrow, indent, Some(isSelfAnnotationNL))
                case _ =>
                  (null, None, 0, None)
              }
          }
        val lambdaPolicy =
          if (lambdaExpire == null) null
          else {
            val arrowOptimal = getOptimalTokenFor(lambdaExpire)
            newlineBeforeClosingCurly &
              SingleLineBlock(arrowOptimal) &
              decideNewlinesOnlyAfterToken(arrowOptimal)
          }

        def getSingleLineDecision = {
          type Classifiers = Seq[Classifier[T, _]]
          def classifiersByParent: Classifiers =
            leftOwner.parent match {
              case Some(_: Term.If) => Seq(T.KwElse.classifier)
              case Some(_: Term.Try | _: Term.TryWithHandler) =>
                Seq(T.KwCatch.classifier, T.KwFinally.classifier)
              case _ => Seq.empty
            }
          val classifiers: Classifiers = leftOwner match {
            // for catch with case, we should go up only one level
            case _: Term.Try if rightOwner.is[Case] =>
              Seq(T.KwFinally.classifier)
            case _ => classifiersByParent
          }

          val breakSingleLineAfterClose = classifiers.nonEmpty && {
            val afterClose = closeFT.right
            classifiers.exists(_(afterClose))
          }
          if (!breakSingleLineAfterClose) Policy.NoPolicy
          else decideNewlinesOnlyAfterClose(close)
        }
        def getClassicSingleLineDecisionOpt =
          if (newlines > 0) None
          else Some(getSingleLineDecision)

        def getSingleLineLambdaDecisionOpt = {
          val ok = !lambdaNLOnly.contains(true) &&
            getSpaceAndNewlineAfterCurlyLambda(newlines)._1
          if (ok) Some(getSingleLineDecision) else None
        }

        // null if skipping
        val singleLineDecisionOpt = style.newlines.source match {
          case Newlines.keep if newlines != 0 => None
          case Newlines.unfold => None
          case Newlines.fold =>
            val isTopLevelBlock =
              leftOwner.parent.exists(_.parent.isEmpty) || (leftOwner match {
                case t: Template =>
                  // false for
                  // new A { () =>
                  //   println("A")
                  // }
                  // but true for
                  // new A {
                  //   def f = x
                  // }
                  !t.parent.exists(_.is[Term.NewAnonymous]) ||
                  t.stats.exists(_.is[Defn])
                case _ => false
              })

            // do not fold top-level blocks
            if (isTopLevelBlock) None
            else if (lambdaPolicy != null) getSingleLineLambdaDecisionOpt
            else
              Some(getSingleLineDecision match {
                case NoPolicy if leftOwner.is[Term.ForYield] =>
                  val postClose = nextNonComment(closeFT).right
                  val bodySlb = SingleLineBlock(getLastToken(leftOwner))
                  new Policy.Delay(bodySlb, Policy.End.On(postClose))
                case x => x
              })
          // old behaviour
          case _ =>
            if (lambdaPolicy == null) getClassicSingleLineDecisionOpt
            else getSingleLineLambdaDecisionOpt
        }

        val singleLineSplit =
          singleLineDecisionOpt.fold(Split.ignored) { sld =>
            val expire = endOfSingleLineBlock(closeFT)
            Split(xmlSpace(leftOwner), 0)
              .withSingleLine(expire, noSyntaxNL = true, killOnFail = true)
              .andPolicy(sld)
          }

        val splits = Seq(
          singleLineSplit,
          Split(nl, 1)
            .withPolicy(newlineBeforeClosingCurly)
            .withIndent(style.indent.main, close, Before),
          Split(Space, 0)
            .onlyIf(lambdaNLOnly.contains(false) && lambdaPolicy != null)
            .notIf(style.newlines.keepBreak(newlines))
            .withOptimalTokenOpt(lambdaArrow)
            .withIndent(lambdaIndent, close, Before)
            .withPolicy(lambdaPolicy)
        )
        right match {
          case t: T.Xml.Start => withIndentOnXmlStart(t, splits)
          case _ => splits
        }

      case FormatToken(T.RightArrow() | T.ContextArrow(), right, _)
          if startsStatement(right).isDefined &&
            leftOwner.isInstanceOf[Term.FunctionTerm] =>
        val leftFuncBody = leftOwner.asInstanceOf[Term.FunctionTerm].body
        val endOfFunction = getLastNonTrivialToken(leftFuncBody)
        val canBeSpace =
          startsStatement(right).get.isInstanceOf[Term.FunctionTerm]
        val (afterCurlySpace, afterCurlyNewlines) =
          getSpaceAndNewlineAfterCurlyLambda(newlines)
        val spaceSplit =
          if (canBeSpace) Split(Space, 0)
          else if (
            afterCurlySpace &&
            (!rightOwner.is[Defn] || style.newlines.source.eq(Newlines.fold))
          )
            Split(Space, 0).withSingleLineNoOptimal(
              getOptimalTokenFor(endOfFunction),
              noSyntaxNL = true
            )
          else Split.ignored
        Seq(
          spaceSplit,
          Split(afterCurlyNewlines, 1)
            .withIndent(style.indent.main, endOfFunction, After)
        )

      case FormatToken(T.RightArrow() | T.ContextArrow(), right, _)
          if leftOwner.is[Term.FunctionTerm] ||
            leftOwner.is[Term.PolyFunction] ||
            (leftOwner.is[Template] &&
              leftOwner.parent.exists(_.is[Term.NewAnonymous])) =>
        val (endOfFunction, expiresOn) = leftOwner match {
          case t: Term.FunctionTerm => functionExpire(t)
          case t => getLastNonTrivialToken(t) -> ExpiresOn.Before
        }

        val hasSingleLineComment = tokens.isRightCommentThenBreak(formatToken)
        val indent = // don't indent if the body is empty `{ x => }`
          if (isEmptyFunctionBody(leftOwner) && !right.is[T.Comment]) 0
          else if (leftOwner.is[Template]) 0 // { applied the indent
          else style.indent.main

        def noSingleLine = {
          // for constructors with empty args lambda
          // new Foo { () =>
          //   println("wow")
          // }
          val isCurlyLambda =
            leftOwner.is[Template] || leftOwner.parent.exists(_.is[Template])

          def noSquash =
            style.newlines.afterCurlyLambdaParams ne
              Newlines.AfterCurlyLambdaParams.squash

          style.newlines.source match {
            case Newlines.fold => false
            case Newlines.unfold => isCurlyLambda && noSquash
            case Newlines.keep => newlines != 0
            case Newlines.classic => isCurlyLambda && newlines != 0 && noSquash
          }
        }
        val singleLineSplit =
          Split(Space, 0)
            .notIf(hasSingleLineComment || noSingleLine)
            .withSingleLineNoOptimal(endOfFunction)
        def newlineSplit =
          Split(Newline, 1 + nestedApplies(leftOwner))
            .withIndent(indent, endOfFunction, expiresOn)
        val multiLineSplits =
          if (hasSingleLineComment)
            Seq(newlineSplit)
          else {
            // 2020-01: break after same-line comments, and any open brace
            val nonComment = nextNonCommentSameLine(formatToken)
            val hasBlock = nonComment.right.is[T.LeftBrace] &&
              (matching(nonComment.right) eq endOfFunction)
            if (!hasBlock && (nonComment eq formatToken))
              Seq(newlineSplit)
            else {
              // break after the brace or comment if fits, or now if doesn't
              // if brace, don't add indent, the LeftBrace rule will do that
              val spaceIndent = if (hasBlock) 0 else indent
              Seq(
                Split(Space, 0)
                  .withIndent(spaceIndent, endOfFunction, expiresOn)
                  .withOptimalToken(getOptimalTokenFor(next(nonComment))),
                newlineSplit
              )
            }
          }
        singleLineSplit +: multiLineSplits

      // Case arrow
      case tok @ FormatToken(_: T.RightArrow, _, _) if leftOwner.is[CaseTree] =>
        val owner = leftOwner.asInstanceOf[CaseTree]
        val body = owner.body
        val condIsDefined = leftOwner match {
          case c: Case => c.cond.isDefined
          case _ => false
        }
        val bodyIsEmpty = body.tokens.isEmpty
        def baseSplit = Split(Space, 0)
        def nlSplit(ft: FormatToken)(cost: Int)(implicit l: FileLine) =
          Split(NewlineT(isDouble = ft.hasBlankLine && bodyIsEmpty), cost)
        CtrlBodySplits.checkComment(tok, nlSplit(tok)) { ft =>
          def withSlbSplit(implicit l: FileLine) = Seq(
            baseSplit.withSingleLine(getLastNonTrivialToken(body)),
            nlSplit(ft)(1)(nextLine)
          )
          val beforeMultiline = style.newlines.getBeforeMultiline
          if (isCaseBodyABlock(ft, owner)) Seq(baseSplit)
          else if (isCaseBodyEnclosedAsBlock(ft, owner)) Seq(baseSplit)
          else if (ft.right.is[T.KwCase]) Seq(nlSplit(ft)(0))
          else if (beforeMultiline eq Newlines.unfold) {
            if (ft.right.is[T.Semicolon]) Seq(baseSplit, nlSplit(ft)(1))
            else if (style.newlines.source ne Newlines.unfold) withSlbSplit
            else Seq(nlSplit(ft)(0))
          } else if (ft.hasBreak && !beforeMultiline.ignoreSourceSplit)
            Seq(nlSplit(ft)(0))
          else if (bodyIsEmpty) Seq(baseSplit, nlSplit(ft)(1))
          else if (
            condIsDefined ||
            beforeMultiline.eq(Newlines.classic) ||
            getSingleStatExceptEndMarker(body).isEmpty
          ) withSlbSplit
          else {
            val isKeep = beforeMultiline.eq(Newlines.keep)
            CtrlBodySplits.foldedNonEmptyNonComment(body, nlSplit(ft), isKeep)
          }
        }
      // New statement
      case tok @ FormatToken(_: T.Semicolon, _, StartsStatementRight(stmt))
          if newlines == 0 =>
        val spaceSplit =
          if (style.newlines.source eq Newlines.unfold) Split.ignored
          else {
            val expire = getLastToken(stmt)
            Split(Space, 0).withSingleLine(expire)
          }
        Seq(
          spaceSplit,
          // For some reason, this newline cannot cost 1.
          Split(NewlineT(isDouble = tok.hasBlankLine), 0)
        )

      case FormatToken(_: T.RightParen, _, _)
          if leftOwner.is[Defn.ExtensionGroup] &&
            nextNonComment(formatToken).right.isNot[LeftParenOrBrace] =>
        val expireToken = getLastToken(leftOwner)
        def nlSplit(cost: Int = 0)(implicit fileLine: FileLine) =
          Split(Newline, cost).withIndent(style.indent.main, expireToken, After)
        style.newlines.source match {
          case Newlines.unfold =>
            Seq(nlSplit())
          case Newlines.keep if newlines != 0 =>
            Seq(nlSplit())
          case _ =>
            Seq(
              Split(Space, 0).withSingleLine(expireToken),
              nlSplit(cost = 1)
            )
        }

      case tok @ FormatToken(left, right, StartsStatementRight(_)) =>
        val expire = rightOwner.tokens
          .find(_.is[T.Equals])
          .fold(getLastToken(rightOwner)) { equalsToken =>
            val equalsFormatToken = tokens(equalsToken)
            if (equalsFormatToken.right.is[T.LeftBrace]) {
              equalsFormatToken.right
            } else {
              equalsToken
            }
          }

        val annoRight = right.is[T.At]
        val annoLeft = isSingleIdentifierAnnotation(prev(tok))

        if (
          (annoRight || annoLeft) &&
          style.optIn.annotationNewlines && !style.newlines.sourceIgnored
        )
          Seq(Split(getMod(formatToken), 0))
        else {
          maybeGetInfixSplitsBeforeLhs(
            formatToken,
            Some(
              if (left.is[T.Comment] && tok.noBreak) Space
              else NewlineT(isDouble = tok.hasBlankLine)
            )
          ) {
            val spaceCouldBeOk = annoLeft && (style.newlines.source match {
              case Newlines.unfold =>
                right.is[T.Comment] ||
                !style.optIn.annotationNewlines && annoRight
              case Newlines.fold =>
                right.is[T.Comment] || annoRight ||
                !style.optIn.annotationNewlines && right.is[Keyword]
              case Newlines.keep =>
                newlines == 0 && (annoRight || right.is[Keyword])
              case _ =>
                newlines == 0 && right.is[Keyword]
            })
            Seq(
              // This split needs to have an optimalAt field.
              Split(Space, 0)
                .onlyIf(spaceCouldBeOk)
                .withSingleLine(expire),
              // For some reason, this newline cannot cost 1.
              Split(NewlineT(isDouble = tok.hasBlankLine), 0)
            )
          }
        }

      case FormatToken(_, T.RightBrace(), _) =>
        Seq(
          Split(xmlSpace(rightOwner), 0),
          Split(NewlineT(isDouble = formatToken.hasBlankLine), 0)
        )
      case FormatToken(_: T.KwPackage, _, _) if leftOwner.is[Pkg] =>
        Seq(
          Split(Space, 0)
        )
      // Opening [ with no leading space.
      // Opening ( with no leading space.
      case ft @ FormatToken(_, open @ LeftParenOrBracket(), _)
          if noSpaceBeforeOpeningParen(rightOwner) &&
            leftOwner.parent.forall {
              // infix applications have no space.
              case _: Type.ApplyInfix | _: Term.ApplyInfix => false
              case _ => true
            } && (prevNonComment(formatToken).left match {
              case RightParenOrBracket() | T.KwSuper() | T.KwThis() |
                  T.Ident(_) | T.RightBrace() | T.Underscore() |
                  T.Constant.Symbol(_) =>
                true
              case _ => false
            }) =>
        def modification: Modification = leftOwner match {
          case _: Mod => Space
          // Add a space between constructor annotations and their parameter lists
          // see:
          // https://github.com/scalameta/scalafmt/pull/1516
          // https://github.com/scalameta/scalafmt/issues/1528
          case init: Init if init.parent.forall(_.is[Mod.Annot]) => Space
          case t: Term.Name
              if style.spaces.afterTripleEquals && t.value == "===" =>
            Space
          case name: Term.Name
              if style.spaces.afterSymbolicDefs &&
                isSymbolicName(name.value) && name.parent.exists(isDefDef) =>
            Space
          case _: Defn.ExtensionGroup
              if formatToken.left.is[soft.KwExtension] =>
            Space
          case _ => Space(ft.left.is[T.Comment])
        }
        val defn = isDefnSite(rightOwner)
        val defRhs = if (defn) defDefBody(rightOwner) else None
        val beforeDefRhs = defRhs.flatMap(tokens.tokenJustBeforeOpt)
        def getSplitsBeforeOpenParen(
            src: Newlines.SourceHints,
            indentLen: Int,
            shouldAlignBefore: Align => Boolean
        )(getArgsOpt: => Option[Seq[Tree]]) = {
          val close = matching(open)
          val indent = Indent(indentLen, close, ExpiresOn.After)
          val isAlignFirstParen = shouldAlignBefore(style.align) &&
            !prevNonComment(ft).left.is[T.RightParen]
          def noSplitSplit(implicit fileLine: FileLine) =
            if (isAlignFirstParen) Split(NoSplit, 0)
            else Split(NoSplit, 0).withSingleLine(close)
          val splits = src match {
            case Newlines.unfold =>
              val slbEnd =
                if (defn) beforeDefRhs.fold(getLastToken(rightOwner))(_.left)
                else getLastToken(getLastCall(rightOwner))
              val multipleArgs = isSeqMulti(getApplyArgs(next(ft), false).args)
              val nft = tokens.tokenAfter(close)
              val nlPolicy = nft.right match {
                case t: T.LeftParen => decideNewlinesOnlyBeforeClose(t)
                case t: T.Colon
                    if defn && (nft.left.is[T.Comment] ||
                      style.newlines.sometimesBeforeColonInMethodReturnType
                      && defDefReturnType(rightOwner).isDefined) =>
                  decideNewlinesOnlyBeforeClose(t)
                case _ => NoPolicy
              }
              Seq(
                Split(NoSplit, 0).withSingleLine(slbEnd),
                Split(Newline, 1)
                  .withIndent(indent)
                  .withPolicy(
                    penalizeNewlineByNesting(open, close),
                    multipleArgs
                  )
                  .andPolicy(nlPolicy)
              )
            case Newlines.keep =>
              if (newlines != 0)
                Seq(Split(Newline, 0).withIndent(indent))
              else
                Seq(
                  noSplitSplit,
                  Split(Newline, 1).withIndent(indent)
                )
            case _ =>
              def nlColonPolicy =
                if (!style.newlines.sometimesBeforeColonInMethodReturnType) None
                else if (!defn) None
                else
                  defDefReturnType(rightOwner).flatMap { declTpe =>
                    tokenBefore(declTpe).left match {
                      case t: T.Colon => Some(decideNewlinesOnlyBeforeClose(t))
                      case _ => None
                    }
                  }
              Seq(
                noSplitSplit,
                Split(Newline, 1)
                  .withIndent(indent)
                  .withPolicyOpt(nlColonPolicy)
              )
          }
          val argsOpt = if (isAlignFirstParen) getArgsOpt else None
          argsOpt.map(x => tokens.tokenAfter(x).right).fold(splits) { x =>
            val noSplitIndents = Seq(
              Indent(StateColumn, x, ExpiresOn.Before),
              Indent(-indentLen, x, ExpiresOn.Before)
            )
            splits.map { s =>
              if (s.isNL) s else s.withIndents(noSplitIndents)
            }
          }
        }
        val beforeOpenParenSplits =
          if (!open.is[T.LeftParen]) None
          else if (defn)
            style.newlines.getBeforeOpenParenDefnSite.map { x =>
              val beforeBody = defRhs.flatMap {
                case t: Template => templateCurlyFt(t)
                case _ => beforeDefRhs
              }
              val indent = beforeBody.fold(style.indent.main) { y =>
                val ob = OptionalBraces.get(y).nonEmpty
                style.indent.extraBeforeOpenParenDefnSite +
                  (if (ob) style.indent.getSignificant else style.indent.main)
              }
              getSplitsBeforeOpenParen(x, indent, _.beforeOpenParenDefnSite) {
                rightOwner match {
                  case SplitDefnIntoParts(_, _, _, args) => Some(args.last)
                  case _ => None
                }
              }
            }
          else if (style.dialect.allowSignificantIndentation)
            style.newlines.getBeforeOpenParenCallSite.map { x =>
              val indent = style.indent.getSignificant
              @tailrec
              def findLastCallArgs(tree: Tree, ca: CallArgs): CallArgs =
                tree match {
                  case SplitCallIntoParts(_, pca) =>
                    tree.parent match {
                      case Some(p) => findLastCallArgs(p, pca)
                      case _ => pca
                    }
                  case _ => ca
                }
              getSplitsBeforeOpenParen(x, indent, _.beforeOpenParenCallSite) {
                Option(findLastCallArgs(rightOwner, null))
                  .map(_.fold(identity, _.last))
              }
            }
          else None
        beforeOpenParenSplits.getOrElse(Seq(Split(modification, 0)))

      // Defn.{Object, Class, Trait, Enum}
      case FormatToken(
            _: T.KwObject | _: T.KwClass | _: T.KwTrait | _: T.KwEnum,
            _,
            _
          ) =>
        def expire = defnTemplate(leftOwner)
          .flatMap {
            getTemplateGroups(_).flatMap(
              _.lastOption.flatMap(_.headOption.flatMap(_.tokens.headOption))
            )
          }
          .getOrElse(getLastToken(leftOwner))
        def forceNewlineBeforeExtends = Policy.before(expire) {
          case Decision(t @ FormatToken(_, soft.ExtendsOrDerives(), _), s)
              if t.meta.rightOwner.parent.contains(leftOwner) =>
            s.filter(x => x.isNL && !x.isActiveFor(SplitTag.OnelineWithChain))
        }
        val policy =
          if (style.binPack.keepParentConstructors) None
          else
            defnBeforeTemplate(leftOwner).map { x =>
              val policyEnd = Policy.End.On(x.tokens.last)
              delayedBreakPolicy(policyEnd)(forceNewlineBeforeExtends)
            }
        Seq(Split(Space, 0).withPolicyOpt(policy))
      // DefDef
      case FormatToken(_: T.KwDef, _: T.Ident, _) =>
        Seq(Split(Space, 0))
      case ft @ FormatToken(_: T.Equals, _, SplitAssignIntoPartsLeft(parts)) =>
        maybeGetInfixSplitsBeforeLhs(ft) {
          val (rhs, paramss) = parts
          getSplitsDefValEquals(ft, rhs) {
            if (paramss.isDefined) getSplitsDefEquals(ft, rhs)
            else getSplitsValEquals(ft, rhs)(getSplitsValEqualsClassic(ft, rhs))
          }
        }

      // Parameter opening for one parameter group. This format works
      // on the WHOLE defnSite (via policies)
      case ft @ FormatToken(LeftParenOrBracket(), _, _)
          if style.verticalMultiline.atDefnSite &&
            isDefnSiteWithParams(leftOwner) =>
        verticalMultiline(leftOwner, ft)(style)

      // Term.Apply and friends
      case FormatToken(_: T.LeftParen, _, LambdaAtSingleArgCallSite(lambda)) =>
        val close = matching(formatToken.left)
        val newlinePolicy =
          if (!style.danglingParentheses.callSite) None
          else Some(decideNewlinesOnlyBeforeClose(close))
        val noSplitMod =
          if (style.newlines.alwaysBeforeCurlyLambdaParams) null
          else getNoSplit(formatToken, true)

        def multilineSpaceSplit(implicit fileLine: FileLine): Split = {
          val lambdaLeft: Option[T] =
            matchingOpt(functionExpire(lambda)._1).filter(_.is[T.LeftBrace])

          val arrowFt = getFuncArrow(lambda).get
          val lambdaIsABlock = lambdaLeft.contains(arrowFt.right)
          val lambdaToken =
            getOptimalTokenFor(if (lambdaIsABlock) next(arrowFt) else arrowFt)

          val spacePolicy = SingleLineBlock(lambdaToken) | {
            if (lambdaIsABlock) None
            else
              newlinePolicy.map(
                delayedBreakPolicy(Policy.End.On(lambdaLeft.getOrElse(close)))
              )
          }
          Split(noSplitMod, 0)
            .withPolicy(spacePolicy)
            .withOptimalToken(lambdaToken)
        }

        if (noSplitMod == null)
          Seq(
            Split(Newline, 0)
              .withPolicyOpt(newlinePolicy)
              .withIndent(style.indent.callSite, close, Before)
          )
        else {
          val newlinePenalty = 3 + nestedApplies(leftOwner)
          val noMultiline = style.newlines.beforeCurlyLambdaParams eq
            Newlines.BeforeCurlyLambdaParams.multiline
          Seq(
            Split(noSplitMod, 0).withSingleLine(close),
            if (noMultiline) Split.ignored else multilineSpaceSplit,
            Split(Newline, newlinePenalty)
              .withPolicyOpt(newlinePolicy)
              .withIndent(style.indent.callSite, close, Before)
          )
        }

      case FormatToken(T.LeftParen(), T.RightParen(), _) =>
        val noNL = style.newlines.sourceIgnored || formatToken.noBreak
        Seq(Split(NoSplit.orNL(noNL), 0))

      case tok @ FormatToken(open @ LeftParenOrBracket(), right, _) if {
            if (isCallSite(leftOwner))
              style.binPack.callSite(open).isNever &&
              !isSuperfluousParenthesis(formatToken.left, leftOwner)
            else
              style.binPack.defnSite(open).isNever &&
              isDefnSite(leftOwner)
          } =>
        val close = matching(open)
        val TreeArgs(lhs, args) = getApplyArgs(formatToken, false)
        // In long sequence of select/apply, we penalize splitting on
        // parens furthest to the right.
        val lhsPenalty = treeDepth(lhs)

        // XXX: sometimes we have zero args, so multipleArgs != !singleArgument
        val numArgs = args.length
        val singleArgument = numArgs == 1
        val multipleArgs = numArgs > 1
        val notTooManyArgs = multipleArgs && numArgs <= 100

        val isBracket = open.is[T.LeftBracket]
        val bracketCoef = if (isBracket) Constants.BracketPenalty else 1

        val rightIsComment = right.is[T.Comment]
        val onlyConfigStyle = mustUseConfigStyle(formatToken)

        val sourceIgnored = style.newlines.sourceIgnored
        val isSingleEnclosedArgument =
          singleArgument && tokens.isEnclosedInMatching(args(0))
        val useConfigStyle = onlyConfigStyle || (sourceIgnored &&
          style.optIn.configStyleArguments && !isSingleEnclosedArgument)

        val nestedPenalty = 1 + nestedApplies(leftOwner) + lhsPenalty

        val tupleSite = isTuple(leftOwner)
        val anyDefnSite = isDefnSite(leftOwner)
        val defnSite = !tupleSite && anyDefnSite

        val indent =
          if (anyDefnSite)
            Num(style.indent.getDefnSite(leftOwner))
          else
            Num(style.indent.callSite)

        val closeFormatToken = tokens(close)
        val isBeforeOpenParen =
          if (defnSite)
            style.newlines.isBeforeOpenParenDefnSite
          else
            style.newlines.isBeforeOpenParenCallSite
        val expirationToken: T =
          if (isBeforeOpenParen) close
          else if (defnSite && !isBracket)
            defnSiteLastToken(closeFormatToken, leftOwner)
          else
            rhsOptimalToken(closeFormatToken)

        val mustDangleForTrailingCommas =
          getMustDangleForTrailingCommas(prev(closeFormatToken))

        val mustDangle = onlyConfigStyle || expirationToken.is[T.Comment] ||
          mustDangleForTrailingCommas
        val shouldDangle =
          if (defnSite) !shouldNotDangleAtDefnSite(leftOwner, false)
          else style.danglingParentheses.tupleOrCallSite(tupleSite)
        val wouldDangle = shouldDangle || {
          val beforeClose = prev(closeFormatToken)
          beforeClose.hasBreak && beforeClose.left.is[T.Comment]
        }

        val newlinePolicy: Policy =
          if (wouldDangle || mustDangle) {
            decideNewlinesOnlyBeforeClose(close)
          } else {
            Policy.NoPolicy
          }

        // covers using as well
        val handleImplicit = !tupleSite && (
          if (onlyConfigStyle) opensConfigStyleImplicitParamList(formatToken)
          else opensImplicitParamList(formatToken, args)
        )

        val noSplitMod =
          if (
            style.newlines.keepBreak(tok) || {
              if (!handleImplicit) onlyConfigStyle
              else style.newlines.forceBeforeImplicitParamListModifier
            }
          )
            null
          else getNoSplit(formatToken, !isBracket)
        val noSplitIndent = if (rightIsComment) indent else Num(0)

        val align = !rightIsComment && {
          if (tupleSite) style.align.getOpenParenTupleSite
          else style.align.getOpenDelimSite(isBracket, defnSite)
        } && (!handleImplicit ||
          style.newlines.forceAfterImplicitParamListModifier)
        val alignTuple = align && tupleSite && !onlyConfigStyle

        val keepConfigStyleSplit = !sourceIgnored &&
          style.optIn.configStyleArguments && newlines != 0
        val splitsForAssign =
          if (defnSite || isBracket || keepConfigStyleSplit) None
          else
            getAssignAtSingleArgCallSite(leftOwner).map { assign =>
              val assignToken = assign.rhs match {
                case b: Term.Block => tokens.getHead(b)
                case _ => tokens(assign.tokens.find(_.is[T.Equals]).get)
              }
              val breakToken = getOptimalTokenFor(assignToken)
              val newlineAfterAssignDecision =
                if (newlinePolicy.isEmpty) Policy.NoPolicy
                else decideNewlinesOnlyAfterToken(breakToken)
              Seq(
                Split(Newline, nestedPenalty + Constants.ExceedColumnPenalty)
                  .withPolicy(newlinePolicy)
                  .withIndent(indent, close, Before),
                Split(NoSplit, nestedPenalty)
                  .withSingleLine(breakToken)
                  .andPolicy(newlinePolicy & newlineAfterAssignDecision)
              )
            }

        def isExcludedTree(tree: Tree): Boolean =
          tree match {
            case t: Init => t.argss.nonEmpty
            case t: Term.Apply => t.args.nonEmpty
            case t: Term.ApplyType => t.targs.nonEmpty
            case t: Term.Match => t.cases.nonEmpty
            case t: Type.Match => t.cases.nonEmpty
            case t: Term.New => t.init.argss.nonEmpty
            case _: Term.NewAnonymous => true
            case _ => false
          }

        val excludeBlocks =
          if (isBracket) {
            val excludeBeg = if (align) tokens.getHead(args.last) else tok
            insideBlock[T.LeftBracket](excludeBeg, close)
          } else if (
            multipleArgs ||
            !isSingleEnclosedArgument &&
            style.newlines.source.eq(Newlines.unfold)
          )
            TokenRanges.empty
          else if (
            style.newlines.source.eq(Newlines.fold) && {
              isSingleEnclosedArgument ||
              singleArgument && isExcludedTree(args(0))
            }
          )
            parensTuple(tokens.getLast(args(0)).left)
          else insideBracesBlock(tok, close)

        def singleLine(
            newlinePenalty: Int
        )(implicit fileLine: FileLine): Policy = {
          if (multipleArgs && (isBracket || excludeBlocks.ranges.isEmpty)) {
            SingleLineBlock(close, noSyntaxNL = true)
          } else if (isBracket) {
            PenalizeAllNewlines(
              close,
              newlinePenalty,
              penalizeLambdas = false
            )
          } else {
            val penalty =
              if (!multipleArgs) newlinePenalty
              else Constants.ShouldBeNewline
            policyWithExclude(excludeBlocks, Policy.End.On, Policy.End.On)(
              Policy.End.Before(close),
              new PenalizeAllNewlines(
                _,
                penalty = penalty,
                penalizeLambdas = multipleArgs,
                noSyntaxNL = multipleArgs
              )
            )
          }
        }

        val keepNoNL = style.newlines.source.eq(Newlines.keep) && tok.noBreak
        val preferNoSplit = keepNoNL && singleArgument
        val oneArgOneLine = newlinePolicy & splitOneArgOneLine(close, leftOwner)
        val extraOneArgPerLineIndent =
          if (multipleArgs && style.poorMansTrailingCommasInConfigStyle)
            Indent(Num(2), right, After)
          else Indent.Empty
        val (implicitPenalty, implicitPolicy) =
          if (!handleImplicit) (2, Policy.NoPolicy)
          else (0, decideNewlinesOnlyAfterToken(right))

        val splitsNoNL =
          if (noSplitMod == null) Seq.empty
          else if (onlyConfigStyle)
            Seq(
              Split(noSplitMod, 0)
                .withPolicy(oneArgOneLine & implicitPolicy)
                .withOptimalToken(right, killOnFail = true)
                .withIndent(extraOneArgPerLineIndent)
                .withIndent(indent, close, Before)
            )
          else {
            val noSplitPolicy =
              if (preferNoSplit && splitsForAssign.isEmpty) singleLine(2)
              else if (wouldDangle || mustDangle && isBracket || useConfigStyle)
                SingleLineBlock(
                  close,
                  exclude = excludeBlocks,
                  noSyntaxNL = multipleArgs
                )
              else if (splitsForAssign.isDefined)
                singleLine(3)
              else
                singleLine(10)
            Seq(
              Split(noSplitMod, 0, policy = noSplitPolicy)
                .notIf(mustDangleForTrailingCommas)
                .withOptimalToken(expirationToken)
                .withIndent(noSplitIndent, close, Before),
              Split(noSplitMod, (implicitPenalty + lhsPenalty) * bracketCoef)
                .withPolicy(oneArgOneLine & implicitPolicy)
                .onlyIf(
                  (notTooManyArgs && align) || (handleImplicit &&
                    style.newlines.notBeforeImplicitParamListModifier)
                )
                .withIndents(
                  if (align) getOpenParenAlignIndents(close)
                  else Seq(Indent(indent, close, ExpiresOn.Before))
                )
            )
          }

        val splitsNL =
          if (
            alignTuple || !(onlyConfigStyle ||
              multipleArgs || splitsForAssign.isEmpty)
          )
            Seq.empty
          else {
            val cost =
              if (onlyConfigStyle)
                if (splitsNoNL.isEmpty) 0 else 1
              else
                (if (preferNoSplit) Constants.ExceedColumnPenalty else 0) +
                  bracketCoef * (nestedPenalty + (if (multipleArgs) 2 else 0))
            val split =
              if (multipleArgs)
                Split(Newline, cost, policy = oneArgOneLine)
                  .withIndent(extraOneArgPerLineIndent)
              else {
                val noSplit = !onlyConfigStyle && right.is[T.LeftBrace]
                val noConfigStyle = noSplit ||
                  newlinePolicy.isEmpty || !style.optIn.configStyleArguments
                Split(NoSplit.orNL(noSplit), cost, policy = newlinePolicy)
                  .andPolicy(singleLine(4), !noConfigStyle)
                  .andPolicyOpt(
                    asInfixApp(args.head).map(InfixSplits(_, tok).nlPolicy),
                    !singleArgument
                  )
              }
            Seq(split.withIndent(indent, close, Before))
          }

        splitsNoNL ++ splitsNL ++ splitsForAssign.getOrElse(Seq.empty)

      case ft @ FormatToken(open @ LeftParenOrBracket(), right, _)
          if !style.binPack.defnSite(open).isNever && isDefnSite(leftOwner) =>
        val close = matching(open)
        def slbPolicy = SingleLineBlock(close, okSLC = true, noSyntaxNL = true)
        val baseNoSplitMod = Space(style.spaces.inParentheses)
        if (close eq right)
          Seq(Split(baseNoSplitMod, 0))
        else if (isTuple(leftOwner))
          Seq(Split(baseNoSplitMod, 0).withPolicy(slbPolicy))
        else {
          val isBracket = open.is[T.LeftBracket]
          val indent =
            Indent(style.indent.getDefnSite(leftOwner), close, Before)
          val align = style.align.getOpenDelimSite(isBracket, true)
          val noSplitIndents =
            if (align) getOpenParenAlignIndents(close) else Seq(indent)

          val bracketPenalty =
            if (isBracket) Some(Constants.BracketPenalty) else None
          val penalizeBrackets =
            bracketPenalty.map(p => PenalizeAllNewlines(close, p + 3))
          val onlyConfigStyle = mustUseConfigStyle(formatToken) ||
            getMustDangleForTrailingCommas(close)

          val argsHeadOpt = argumentStarts.get(hash(right))
          val isSingleArg = isSeqSingle(getApplyArgs(ft, false).args)
          val oneline =
            style.binPack.defnSite(isBracket) == BinPack.Unsafe.Oneline
          val nlOnelinePolicy = argsHeadOpt.flatMap { x =>
            if (isSingleArg || !oneline) None
            else
              findFirstOnRight[T.Comma](tokens.getLast(x), close)
                .map(splitOneArgPerLineAfterCommaOnBreak)
          }

          val mustDangle = onlyConfigStyle ||
            style.danglingParentheses.defnSite &&
            (style.newlines.sourceIgnored || !style.optIn.configStyleArguments)
          val slbOnly = mustDangle || style.newlines.source.eq(Newlines.unfold)
          def noSplitPolicy: Policy =
            if (slbOnly) slbPolicy
            else {
              val penalizeOpens = bracketPenalty.fold(Policy.noPolicy) { p =>
                Policy.before(close) {
                  case Decision(ftd @ FormatToken(o: T.LeftBracket, _, m), s)
                      if isDefnSite(m.leftOwner) &&
                        !styleMap.at(o).binPack.defnSite(o).isNever =>
                    if (tokens.isRightCommentThenBreak(ftd)) s
                    else s.map(x => if (x.isNL) x.withPenalty(p) else x)
                }
              }
              val argPolicy = argsHeadOpt.fold(Policy.noPolicy) { x =>
                if (oneline && isSingleArg) NoPolicy
                else SingleLineBlock(x.tokens.last, noSyntaxNL = true)
              }
              argPolicy & (penalizeOpens | penalizeBrackets)
            }
          val rightIsComment = right.is[T.Comment]
          val mustUseNL = onlyConfigStyle ||
            style.newlines.keepBreak(newlines) ||
            rightIsComment &&
            (newlines != 0 || nextNonCommentSameLine(next(ft)).hasBreak)
          val noSplitModification =
            if (rightIsComment && !mustUseNL) getMod(ft) else baseNoSplitMod
          val nlMod = if (rightIsComment && mustUseNL) getMod(ft) else Newline
          val nlDanglePolicy =
            if (mustDangle) decideNewlinesOnlyBeforeClose(close) else NoPolicy
          def nlCost = bracketPenalty.getOrElse(1)

          Seq(
            Split(mustUseNL, 0)(noSplitModification)
              .withOptimalToken(close, ignore = !slbOnly, killOnFail = true)
              .withPolicy(noSplitPolicy)
              .withIndents(noSplitIndents),
            Split(nlMod, if (mustUseNL || slbOnly) 0 else nlCost)
              .withPolicy(nlDanglePolicy & nlOnelinePolicy & penalizeBrackets)
              .withIndent(indent)
          )
        }

      case ft @ FormatToken(open @ LeftParenOrBracket(), right, _)
          if !style.binPack.callSite(open).isNever && isCallSiteLeft(ft) =>
        val close = matching(open)
        val isBracket = open.is[T.LeftBracket]
        val bracketPenalty = if (isBracket) Constants.BracketPenalty else 1

        val args = getApplyArgs(ft, false).args
        val isSingleArg = isSeqSingle(args)
        val firstArg = args.headOption
        val singleArgAsInfix =
          if (isSingleArg) firstArg.flatMap(asInfixApp) else None

        val opensLiteralArgumentList =
          styleMap.opensLiteralArgumentList(formatToken)
        val singleLineOnly =
          style.binPack.literalsSingleLine && opensLiteralArgumentList
        val mustDangleForTrailingCommas = getMustDangleForTrailingCommas(close)

        val onlyConfigStyle = !mustDangleForTrailingCommas &&
          mustUseConfigStyle(formatToken, !opensLiteralArgumentList)
        val rightIsComment = right.is[T.Comment]
        val nlOnly = mustDangleForTrailingCommas || onlyConfigStyle ||
          style.newlines.keepBreak(newlines) ||
          rightIsComment &&
          (newlines != 0 || nextNonCommentSameLine(next(ft)).hasBreak)

        def findComma(ft: FormatToken) = findFirstOnRight[T.Comma](ft, close)

        val oneline = style.binPack.callSite(open) == BinPack.Unsafe.Oneline
        val nextCommaOneline =
          if (!oneline || isSingleArg) None
          else firstArg.map(tokens.getLast).flatMap(findComma)
        val needOnelinePolicy = oneline &&
          (nextCommaOneline.isDefined || followedBySelectOrApply(leftOwner))
        val nextCommaOnelinePolicy = if (needOnelinePolicy) {
          nextCommaOneline.map(splitOneArgPerLineAfterCommaOnBreak)
        } else None

        val indentLen = style.indent.callSite
        val indent = Indent(Num(indentLen), close, Before)
        val noNoSplitIndents = nlOnly ||
          singleArgAsInfix.isDefined ||
          isSingleArg && oneline && !needOnelinePolicy ||
          !isBracket && getAssignAtSingleArgCallSite(leftOwner).isDefined
        val noSplitIndents =
          if (noNoSplitIndents) Nil
          else if (style.binPack.indentCallSiteOnce) {
            @tailrec
            def iter(tree: Tree): Option[T] = tree.parent match {
              case Some(p: Term.Select) => iter(p)
              case Some(p) if isCallSite(p) => Some(getIndentTrigger(p))
              case _ => None
            }
            Seq(iter(leftOwner).fold(indent)(x => Indent.before(indent, x)))
          } else if (
            if (isTuple(leftOwner)) style.align.getOpenParenTupleSite
            else style.align.getOpenDelimSite(false, false)
          ) getOpenParenAlignIndents(close)
          else Seq(indent)
        def baseNoSplit(implicit fileLine: FileLine) =
          Split(Space(style.spaces.inParentheses), 0)
            .withIndents(noSplitIndents)

        val exclude =
          if (!isBracket) insideBracesBlock(formatToken, close)
          else insideBlock[T.LeftBracket](formatToken, close)
        val penalizeNewlinesPolicy =
          policyWithExclude(exclude, Policy.End.Before, Policy.End.On)(
            Policy.End.On(close),
            new PenalizeAllNewlines(_, 3 + indentLen * bracketPenalty)
          )

        val noSplit =
          if (nlOnly) Split.ignored
          else if (
            singleLineOnly ||
            needOnelinePolicy && nextCommaOneline.isEmpty ||
            // multiline binpack is at odds with unfold, at least force a break
            style.newlines.source.eq(Newlines.unfold)
          ) baseNoSplit.withSingleLine(close, noSyntaxNL = true)
          else {
            val opt =
              if (oneline) nextCommaOneline.orElse(Some(close))
              else if (style.newlines.source.eq(Newlines.fold)) None
              else findComma(formatToken).orElse(Some(close))
            def unindentPolicy = Policy.on(close) {
              val excludeOpen = exclude.ranges.map(_.lt).toSet
              UnindentAtExclude(excludeOpen, Num(-indentLen))
            }
            val noSplitPolicy = if (needOnelinePolicy) {
              val alignPolicy = if (noSplitIndents.exists(_.hasStateColumn)) {
                nextCommaOnelinePolicy.map(_ & penalizeNewlinesPolicy)
              } else None
              alignPolicy.getOrElse {
                val slbEnd = nextCommaOneline.getOrElse(close)
                SingleLineBlock(slbEnd, noSyntaxNL = true)
              }
            } else penalizeNewlinesPolicy
            val indentOncePolicy =
              if (style.binPack.indentCallSiteOnce) {
                val trigger = getIndentTrigger(leftOwner)
                Policy.on(close) {
                  case Decision(t @ FormatToken(LeftParenOrBracket(), _, _), s)
                      if isCallSiteLeft(t) =>
                    s.map { x => if (x.isNL) x else x.switch(trigger, false) }
                }
              } else NoPolicy
            baseNoSplit
              .withOptimalTokenOpt(opt)
              .withPolicy(noSplitPolicy)
              .andPolicy(unindentPolicy, !isSingleArg || noSplitIndents.isEmpty)
              .andPolicy(indentOncePolicy)
          }

        val nlPolicy = {
          def newlineBeforeClose = decideNewlinesOnlyBeforeClose(close)
          def binPackOnelinePolicy = if (needOnelinePolicy) {
            nextCommaOnelinePolicy
              .getOrElse(decideNewlinesOnlyBeforeCloseOnBreak(close))
          } else NoPolicy
          if (onlyConfigStyle) {
            if (styleMap.forcedBinPack(leftOwner))
              newlineBeforeClose & binPackOnelinePolicy
            else splitOneArgOneLine(close, leftOwner) | newlineBeforeClose
          } else if (
            mustDangleForTrailingCommas ||
            style.danglingParentheses.tupleOrCallSite(isTuple(leftOwner)) &&
            (style.newlines.sourceIgnored || !style.optIn.configStyleArguments)
          )
            newlineBeforeClose & binPackOnelinePolicy
          else binPackOnelinePolicy
        }
        val nlMod =
          if (rightIsComment && nlOnly) getMod(ft)
          else NewlineT(alt = if (singleLineOnly) Some(NoSplit) else None)
        Seq(
          noSplit,
          Split(nlMod, bracketPenalty * (if (oneline) 4 else 2))
            .withIndent(indent)
            .withSingleLineOpt(if (singleLineOnly) Some(close) else None)
            .andPolicy(nlPolicy)
            .andPolicy(penalizeNewlinesPolicy, singleLineOnly)
            .andPolicyOpt(singleArgAsInfix.map(InfixSplits(_, ft).nlPolicy))
        )

      // Closing def site ): ReturnType
      case FormatToken(left, _: T.Colon, DefDefReturnTypeRight(returnType))
          if style.newlines.sometimesBeforeColonInMethodReturnType
            || left.is[T.Comment] && newlines != 0 =>
        val expireFt = getLastNonTrivial(returnType)
        val expire = expireFt.left
        val sameLineSplit = Space(endsWithSymbolIdent(left))
        val bopSplits = style.newlines.getBeforeOpenParenDefnSite.map { x =>
          val ob = OptionalBraces.get(next(nextNonComment(expireFt))).nonEmpty
          def extraIfBody = style.indent.extraBeforeOpenParenDefnSite
          val indent =
            if (ob) style.indent.getSignificant + extraIfBody
            else
              style.indent.main +
                (if (defDefBody(rightOwner).isEmpty) 0 else extraIfBody)
          Seq(
            Split(sameLineSplit, 0)
              .onlyIf(newlines == 0 || x.ne(Newlines.keep))
              .withSingleLine(expire),
            Split(Newline, 1).withIndent(indent, expire, After)
          )
        }
        bopSplits.getOrElse {
          val penalizeNewlines =
            PenalizeAllNewlines(expire, Constants.BracketPenalty)
          val indent = style.indent.getDefnSite(leftOwner)
          Seq(
            Split(sameLineSplit, 0).withPolicy(penalizeNewlines),
            // Spark style guide allows this:
            // https://github.com/databricks/scala-style-guide#indent
            Split(Newline, Constants.SparkColonNewline)
              .withIndent(indent, expire, After)
              .withPolicy(penalizeNewlines)
          )
        }
      case FormatToken(_: T.Colon, _, DefDefReturnTypeLeft(returnType))
          if style.newlines.avoidInResultType =>
        val expire = getLastNonTrivialToken(returnType)
        val policy = PenalizeAllNewlines(expire, Constants.ShouldBeNewline)
        Seq(Split(Space, 0).withPolicy(policy).withOptimalToken(expire))

      case FormatToken(T.LeftParen(), T.LeftBrace(), _) =>
        Seq(
          Split(NoSplit, 0)
        )

      case FormatToken(_, T.LeftBrace(), _) if isXmlBrace(rightOwner) =>
        withIndentOnXmlSpliceStart(
          formatToken,
          Seq(Split(NoSplit, 0))
        )

      case FormatToken(T.RightBrace(), _, _) if isXmlBrace(leftOwner) =>
        Seq(
          Split(NoSplit, 0)
        )
      // non-statement starting curly brace
      case FormatToken(_: T.Comma, open: T.LeftBrace, _)
          if !style.poorMansTrailingCommasInConfigStyle &&
            isCallSite(leftOwner) =>
        val close = matching(open)
        val binPackIsEnabled = !style.binPack.unsafeCallSite.isNever
        val useSpace = !style.newlines.keepBreak(newlines)
        val singleSplit =
          if (!binPackIsEnabled) Split(Space.orNL(useSpace), 0)
          else Split(Space, 0).onlyIf(useSpace).withSingleLine(close)
        val otherSplits = rightOwner match {
          case _: Term.PartialFunction | Term.Block(
                List(_: Term.Function | _: Term.PartialFunction)
              ) =>
            Seq(Split(Newline, 0))
          case _ =>
            val breakAfter =
              rhsOptimalToken(next(nextNonCommentSameLine(formatToken)))
            val multiLine =
              decideNewlinesOnlyBeforeClose(close) |
                decideNewlinesOnlyAfterToken(breakAfter)
            Seq(
              Split(Newline, 0).withSingleLine(close, killOnFail = true),
              Split(Space, 1, policy = multiLine)
            )
        }
        val oneArgPerLineSplits =
          if (binPackIsEnabled)
            otherSplits.map(_.preActivateFor(SplitTag.OneArgPerLine))
          else otherSplits.map(_.onlyFor(SplitTag.OneArgPerLine))
        singleSplit +: oneArgPerLineSplits

      case FormatToken(
            _: T.MacroSplice | _: T.MacroQuote,
            _: T.LeftBrace | _: T.LeftBracket,
            _
          ) =>
        Seq(Split(NoSplit, 0))
      case FormatToken(_: T.KwMatch, _, _) =>
        val indentLen = style.indent.matchSite.fold(0)(_ - style.indent.main)
        def expire = getLastNonTrivialToken(leftOwner) // should rbrace
        Seq(Split(Space, 0).withIndent(indentLen, expire, ExpiresOn.Before))
      case FormatToken(_, _: T.LeftBrace, _) =>
        Seq(Split(Space, 0))

      // Delim
      case FormatToken(left, _: T.Comma, _)
          if !left.is[T.Comment] || newlines == 0 =>
        Seq(
          Split(NoSplit, 0)
        )
      // These are mostly filtered out/modified by policies.
      case ft @ FormatToken(lc: T.Comma, _: T.Comment, _) =>
        val nextFt = next(ft)
        if (ft.hasBlankLine) Seq(Split(NewlineT(isDouble = true), 0))
        else if (nextFt.hasBreak || ft.meta.right.hasNL)
          Seq(Split(Space.orNL(newlines), 0))
        else if (newlines == 0) {
          val endFt = nextNonCommentSameLine(nextFt)
          val useSpaceOnly = endFt.hasBreak ||
            rightIsCloseDelimToAddTrailingComma(lc, endFt)
          Seq(Split(Space, 0), Split(useSpaceOnly, 1)(Newline))
        } else if (
          !style.comments.willWrap &&
          rightIsCloseDelimToAddTrailingComma(lc, nextNonComment(nextFt))
        ) Seq(Split(Space, 0), Split(Newline, 1))
        else Seq(Split(Newline, 0))
      case FormatToken(_: T.Comma, right, _) if leftOwner.isNot[Template] =>
        val splitsOpt = argumentStarts.get(hash(right)).flatMap { nextArg =>
          val callSite = isCallSite(leftOwner)
          val binPackOpt =
            if (callSite) Some(style.binPack.unsafeCallSite)
            else if (isDefnSite(leftOwner)) Some(style.binPack.unsafeDefnSite)
            else None
          binPackOpt.filter(!_.isNever).map { binPack =>
            val lastFT = tokens.getLast(nextArg)
            val loEnd = leftOwner.tokens.last.end
            val oneline = binPack == BinPack.Unsafe.Oneline
            val nextCommaOrParen =
              findFirst(lastFT, loEnd) {
                case FormatToken(_, _: T.Comma, _) => true
                case FormatToken(_, RightParenOrBracket(), _) => true
                case _ => false
              }
            val optFT = nextCommaOrParen match {
              case Some(ft @ FormatToken(_, _: T.Comma, _)) if oneline => ft
              case Some(FormatToken(_, _: T.RightParen, _))
                  if style.newlines.avoidInResultType =>
                val rt = defDefReturnType(leftOwner) // could be empty tree
                rt.flatMap(tokens.getLastNonTrivialOpt).getOrElse(lastFT)
              case _ => lastFT
            }
            val nlPolicy = (if (oneline) nextCommaOrParen else None) match {
              case Some(FormatToken(_, t: T.Comma, _)) =>
                if (callSite) splitOneArgPerLineAfterCommaOnBreak(t)
                else delayedBreakPolicyFor(t)(decideNewlinesOnlyAfterClose)
              case Some(FormatToken(_, t, _))
                  if !callSite || followedBySelectOrApply(leftOwner) =>
                decideNewlinesOnlyBeforeCloseOnBreak(t)
              case _ => NoPolicy
            }
            val indentOncePolicy =
              if (style.binPack.indentCallSiteOnce) {
                val trigger = getIndentTrigger(leftOwner)
                Policy.on(lastFT.left) {
                  case Decision(t @ FormatToken(LeftParenOrBracket(), _, _), s)
                      if isCallSiteLeft(t) =>
                    s.map { x => if (x.isNL) x else x.switch(trigger, true) }
                }
              } else NoPolicy
            val noSpace = style.newlines.keepBreak(newlines)
            Seq(
              Split(noSpace, 0)(Space)
                .withSingleLine(endOfSingleLineBlock(optFT), noSyntaxNL = true),
              Split(Newline, 1).withPolicy(nlPolicy & indentOncePolicy)
            )
          }
        }
        def altSplits = leftOwner match {
          case _: Term.ApplyInfix if !style.newlines.formatInfix =>
            // Do whatever the user did if infix.
            Seq(Split(Space.orNL(newlines == 0), 0))
          case _: Defn.Val | _: Defn.Var =>
            val indent = style.indent.getDefnSite(leftOwner)
            Seq(
              Split(Space, 0),
              Split(Newline, 1).withIndent(indent, right, After)
            )
          case _: Defn.RepeatedEnumCase if {
                if (!style.newlines.sourceIgnored) newlines != 0
                else style.newlines.source eq Newlines.unfold
              } =>
            Seq(Split(Newline, 0))
          case _ =>
            Seq(Split(Space, 0), Split(Newline, 1))
        }
        splitsOpt.getOrElse(altSplits)
      case FormatToken(_, T.Semicolon(), _) =>
        Seq(
          Split(NoSplit, 0)
        )
      case FormatToken(_: T.KwReturn, _, _) =>
        val mod =
          if (formatToken.hasBlankLine) Newline2x
          else
            leftOwner match {
              case Term.Return(unit @ Lit.Unit()) if unit.tokens.isEmpty =>
                // Always force blank line for Unit "return".
                Newline
              case _ =>
                Space
            }
        Seq(
          Split(mod, 0)
        )

      /*
       * Type Bounds
       */

      case FormatToken(_, _: T.Colon, _) if rightOwner.is[Type.Param] =>
        val tp = rightOwner.asInstanceOf[Type.Param]
        def noNLMod = Space(
          style.spaces.beforeContextBoundColon match {
            case Spaces.BeforeContextBound.Always => true
            case Spaces.BeforeContextBound.IfMultipleBounds =>
              1 < tp.cbounds.length + tp.vbounds.length +
                tp.tbounds.lo.size + tp.tbounds.hi.size
            case _ => false
          }
        )
        getSplitsForTypeBounds(formatToken, noNLMod, tp, _.cbounds)
      case FormatToken(_, _: T.Viewbound, _) if rightOwner.is[Type.Param] =>
        val tp = rightOwner.asInstanceOf[Type.Param]
        getSplitsForTypeBounds(formatToken, Space, tp, _.vbounds)
      /* Type bounds in type definitions and declarations such as:
       * type `Tuple <: Alpha & Beta = Another` or `Tuple <: Alpha & Beta`
       */
      case FormatToken(_, _: T.Subtype | _: T.Supertype, _)
          if rightOwner.is[Type.Bounds] && rightOwner.parent.isDefined =>
        val tbounds = rightOwner.asInstanceOf[Type.Bounds]
        val boundOpt = formatToken.right match {
          case _: T.Subtype => tbounds.hi
          case _: T.Supertype => tbounds.lo
          case _ => None
        }
        val boundEnd = boundOpt.map(getLastNonTrivialToken)
        val typeOwner = rightOwner.parent.get
        getSplitsForTypeBounds(formatToken, Space, typeOwner, boundEnd)

      case FormatToken(left, _: T.Colon, _) =>
        val mod = left match {
          case ident: T.Ident => identModification(ident)
          case _ => NoSplit
        }
        Seq(Split(mod, 0))

      case FormatToken(_, _: T.Dot, _)
          if style.newlines.source.ne(Newlines.keep) &&
            rightOwner.is[Term.Select] && findTreeWithParent(rightOwner) {
              case _: Type.Select | _: Importer | _: Pkg => Some(true)
              case _: Term.Select | SplitCallIntoParts(_, _) => None
              case _ => Some(false)
            }.isDefined =>
        Seq(Split(NoSplit, 0))

      case t @ FormatToken(left, _: T.Dot, _)
          if rightOwner.is[Term.Select] ||
            (rightOwner.is[Term.Match] && dialect.allowMatchAsOperator) =>
        val enclosed = style.encloseSelectChains
        val (expireTree, nextSelect) =
          findLastApplyAndNextSelect(rightOwner, enclosed)
        val thisSelect = rightOwner match {
          case x: Term.Select => SelectLike(x)
          case x: Term.Match => SelectLike(x, getKwMatchAfterDot(t))
        }
        val prevSelect = findPrevSelect(thisSelect, enclosed)
        val expire = tokens.getLastExceptParen(expireTree.tokens).left
        val indentLen = style.indent.main

        def breakOnNextDot: Policy =
          nextSelect.fold(Policy.noPolicy) { selectLike =>
            val tree = selectLike.tree
            Policy.before(selectLike.nameToken) {
              case Decision(t @ FormatToken(_, _: T.Dot, _), s)
                  if t.meta.rightOwner eq tree =>
                val filtered = s.flatMap { x =>
                  val y = x.activateFor(SplitTag.SelectChainSecondNL)
                  if (y.isActive) Some(y) else None
                }
                if (filtered.isEmpty) Seq.empty
                else {
                  val minCost = math.max(0, filtered.map(_.cost).min - 1)
                  filtered.map { x =>
                    val p =
                      x.policy.filter(!_.isInstanceOf[PenalizeAllNewlines])
                    x.copy(cost = x.cost - minCost, policy = p)
                  }
                }
            }
          }
        val baseSplits = style.newlines.getSelectChains match {
          case Newlines.classic =>
            def getNlMod = {
              val endSelect =
                nextSelect.fold(expire)(x => getLastNonTrivialToken(x.qual))
              val altIndent = Indent(-indentLen, endSelect, After)
              NewlineT(alt = Some(ModExt(NoSplit).withIndent(altIndent)))
            }

            val prevChain = inSelectChain(prevSelect, thisSelect, expireTree)
            val nextSelectTree = nextSelect.map(_.tree)
            if (canStartSelectChain(thisSelect, nextSelectTree, expireTree)) {
              val chainExpire =
                if (nextSelect.isEmpty) thisSelect.nameToken else expire
              val nestedPenalty =
                nestedSelect(rightOwner) + nestedApplies(leftOwner)
              // This policy will apply to both the space and newline splits, otherwise
              // the newline is too cheap even it doesn't actually prevent other newlines.
              val penalizeBreaks = PenalizeAllNewlines(chainExpire, 2)
              def slbPolicy =
                SingleLineBlock(
                  chainExpire,
                  getExcludeIf(chainExpire),
                  noSyntaxNL = true
                )
              val newlinePolicy = breakOnNextDot & penalizeBreaks
              val ignoreNoSplit = t.hasBreak &&
                (left.is[T.Comment] || style.optIn.breakChainOnFirstMethodDot)
              val chainLengthPenalty =
                if (
                  style.newlines.penalizeSingleSelectMultiArgList &&
                  nextSelect.isEmpty
                ) {
                  // penalize by the number of arguments in the rhs open apply.
                  // I know, it's a bit arbitrary, but my manual experiments seem
                  // to show that it produces OK output. The key insight is that
                  // many arguments on the same line can be hard to read. By not
                  // putting a newline before the dot, we force the argument list
                  // to break into multiple lines.
                  splitCallIntoParts.lift(tokens(t, 2).meta.rightOwner) match {
                    case Some((_, Left(args))) =>
                      Math.max(0, args.length - 1)
                    case Some((_, Right(argss))) =>
                      Math.max(0, argss.map(_.length).sum - 1)
                    case _ => 0
                  }
                } else 0
              // when the flag is on, penalize break, to avoid idempotence issues;
              // otherwise, after the break is chosen, the flag prohibits nosplit
              val nlBaseCost =
                if (style.optIn.breakChainOnFirstMethodDot && t.noBreak) 3
                else 2
              val nlCost = nlBaseCost + nestedPenalty + chainLengthPenalty
              val nlMod = getNlMod
              Seq(
                Split(!prevChain, 1) { // must come first, for backwards compat
                  if (style.optIn.breaksInsideChains) NoSplit.orNL(t.noBreak)
                  else nlMod
                }
                  .withPolicy(newlinePolicy)
                  .onlyFor(SplitTag.SelectChainSecondNL),
                Split(ignoreNoSplit, 0)(NoSplit)
                  .withPolicy(slbPolicy, prevChain)
                  .andPolicy(penalizeBreaks),
                Split(if (ignoreNoSplit) Newline else nlMod, nlCost)
                  .withPolicy(newlinePolicy)
              )
            } else {
              val isComment = left.is[T.Comment]
              val doBreak = isComment && t.hasBreak
              Seq(
                Split(!prevChain, 1) {
                  if (style.optIn.breaksInsideChains) NoSplit.orNL(t.noBreak)
                  else if (doBreak) Newline
                  else getNlMod
                }
                  .withPolicy(breakOnNextDot)
                  .onlyFor(SplitTag.SelectChainSecondNL),
                Split(if (doBreak) Newline else Space(isComment), 0)
              )
            }

          case _ if left.is[T.Comment] =>
            Seq(Split(Space.orNL(t.noBreak), 0))

          case Newlines.keep =>
            Seq(Split(NoSplit.orNL(t.noBreak), 0))

          case Newlines.unfold =>
            if (prevSelect.isEmpty && nextSelect.isEmpty)
              Seq(Split(NoSplit, 0), Split(Newline, 1))
            else {
              val forcedBreakPolicy = nextSelect.map { selectLike =>
                val tree = selectLike.tree
                Policy.before(selectLike.nameToken) {
                  case d @ Decision(FormatToken(_, _: T.Dot, m), _)
                      if m.rightOwner eq tree =>
                    d.onlyNewlinesWithoutFallback
                }
              }
              Seq(
                Split(NoSplit, 0).withSingleLine(expire, noSyntaxNL = true),
                Split(NewlineT(alt = Some(NoSplit)), 1)
                  .withPolicyOpt(forcedBreakPolicy)
              )
            }

          case Newlines.fold =>
            val end =
              nextSelect.fold(expire)(x => getLastNonTrivialToken(x.qual))
            def exclude = insideBracesBlock(t, end, true)
            Seq(
              Split(NoSplit, 0).withSingleLine(end, exclude),
              Split(NewlineT(alt = Some(NoSplit)), 1)
            )
        }

        val delayedBreakPolicyOpt = nextSelect.map { selectLike =>
          val tree = selectLike.tree
          Policy.before(selectLike.nameToken) {
            case Decision(t @ FormatToken(_, _: T.Dot, _), s)
                if t.meta.rightOwner eq tree =>
              SplitTag.SelectChainFirstNL.activateOnly(s)
            case Decision(t @ FormatToken(l, _: T.Comment, _), s)
                if t.meta.rightOwner.eq(tree) && !l.is[T.Comment] =>
              SplitTag.SelectChainFirstNL.activateOnly(s)
          }
        }

        // trigger indent only on the first newline
        val indent = Indent(indentLen, expire, After)
        val willBreak = nextNonCommentSameLine(tokens(t, 2)).right.is[T.Comment]
        val splits = baseSplits.map { s =>
          if (willBreak || s.isNL) s.withIndent(indent)
          else s.andFirstPolicyOpt(delayedBreakPolicyOpt)
        }

        if (prevSelect.isEmpty) splits
        else baseSplits ++ splits.map(_.onlyFor(SplitTag.SelectChainFirstNL))

      // ApplyUnary
      case FormatToken(T.Ident(_), Literal(), _) if leftOwner == rightOwner =>
        Seq(
          Split(NoSplit, 0)
        )
      case FormatToken(op @ T.Ident(_), right, _) if leftOwner.parent.exists {
            case unary: Term.ApplyUnary =>
              unary.op.tokens.head == op
            case _ => false
          } =>
        Seq(
          Split(Space(isSymbolicIdent(right)), 0)
        )
      // Annotations, see #183 for discussion on this.
      case FormatToken(_, _: T.At, _) if rightOwner.is[Pat.Bind] =>
        Seq(
          Split(Space, 0)
        )
      case FormatToken(_: T.At, _, _) if leftOwner.is[Pat.Bind] =>
        Seq(
          Split(Space, 0)
        )
      case FormatToken(T.At(), Delim(), _) =>
        Seq(Split(NoSplit, 0))
      case FormatToken(T.At(), right @ T.Ident(_), _) =>
        // Add space if right starts with a symbol
        Seq(Split(identModification(right), 0))

      // Enum Case
      case FormatToken(_, T.KwExtends(), _)
          if rightOwner.isInstanceOf[Defn.EnumCase] =>
        val enumCase = rightOwner.asInstanceOf[Defn.EnumCase]
        binPackParentConstructorSplits(
          true,
          Set(rightOwner),
          enumCase.inits.headOption,
          getLastToken(rightOwner),
          style.indent.extendSite,
          enumCase.inits.lengthCompare(1) > 0
        )

      // Template
      case FormatToken(_, soft.ExtendsOrDerives(), _) =>
        val template = defnTemplate(rightOwner)
        def lastToken = template.fold(getLastNonTrivialToken(rightOwner)) { x =>
          templateDerivesOrCurlyOrLastNonTrivial(x)
        }

        binPackParentConstructorSplits(
          true,
          template.toSet,
          template.flatMap(findTemplateGroupOnRight(_.superType)),
          lastToken,
          style.indent.extendSite,
          template.exists(x =>
            if (x.early.nonEmpty) x.inits.nonEmpty
            else x.inits.lengthCompare(1) > 0
          )
        )

      // trait A extends B, C, D, E
      case FormatToken(_: T.Comma, _, _) if leftOwner.is[Template] =>
        val template = leftOwner.asInstanceOf[Template]
        typeTemplateSplits(template, style.indent.commaSiteRelativeToExtends)

      case FormatToken(_, _: T.KwWith, _) =>
        rightOwner match {
          // something like new A with B with C
          case template: Template if template.parent.exists { p =>
                p.is[Term.New] || p.is[Term.NewAnonymous] || p.is[Defn.Given]
              } =>
            binPackParentConstructorSplits(
              isFirstInit(template, leftOwner),
              Set(template),
              findTemplateGroupOnRight(_.superType)(template),
              templateCurlyOrLastNonTrivial(template),
              style.indent.main,
              template.inits.lengthCompare(1) > 0
            )
          // trait A extends B with C with D with E
          case template: Template =>
            typeTemplateSplits(template, style.indent.withSiteRelativeToExtends)
          case t @ WithChain(top) =>
            binPackParentConstructorSplits(
              !t.lhs.is[Type.With],
              withChain(top).toSet,
              Some(t.rhs),
              top.tokens.last,
              style.indent.main
            )
          case enumCase: Defn.EnumCase =>
            val indent = style.indent.withSiteRelativeToExtends
            val expire = getLastToken(enumCase)
            Seq(
              Split(Space, 0).withIndent(indent, expire, ExpiresOn.After),
              Split(Newline, 1)
                .withIndent(indent, expire, ExpiresOn.After)
            )
          case _ =>
            Seq(Split(Space, 0))
        }
      // If/For/While/For with (
      case FormatToken(open: T.LeftParen, _, _) if (leftOwner match {
            case _: Term.If | _: Term.While | _: Term.For | _: Term.ForYield =>
              !isFirstToken(open, leftOwner)
            case _ => false
          }) =>
        val close = matching(open)
        val indentLen = style.indent.ctrlSite.getOrElse(style.indent.callSite)
        def indents =
          if (style.align.openParenCtrlSite) getOpenParenAlignIndents(close)
          else Seq(Indent(indentLen, close, ExpiresOn.Before))
        val penalizeNewlines = penalizeNewlineByNesting(open, close)
        if (style.danglingParentheses.ctrlSite) {
          val noSplit =
            if (style.align.openParenCtrlSite)
              Split(NoSplit, 0)
                .withIndents(indents)
                .withOptimalToken(close)
                .withPolicy(penalizeNewlines)
                .andPolicy(decideNewlinesOnlyBeforeCloseOnBreak(close))
            else
              Split(NoSplit, 0).withSingleLine(close)
          Seq(
            noSplit,
            Split(Newline, 1)
              .withIndent(indentLen, close, Before)
              .withPolicy(penalizeNewlines)
              .andPolicy(decideNewlinesOnlyBeforeClose(close))
          )
        } else
          Seq(
            Split(NoSplit, 0)
              .withIndents(indents)
              .withPolicy(penalizeNewlines)
          )
      case FormatToken(_: T.KwIf, right, _) if leftOwner.is[Term.If] =>
        val owner = leftOwner.asInstanceOf[Term.If]
        val expireTree = if (ifWithoutElse(owner)) owner else owner.elsep
        val expire = rhsOptimalToken(tokens.getLast(expireTree))
        val mod =
          if (style.newlines.keepBreak(newlines)) Newline
          else Space(style.spaces.isSpaceAfterKeyword(right))
        val slb =
          Split(mod.isNewline, 0)(mod).withSingleLine(expire, killOnFail = true)
        val mlSplitBase = Split(mod, if (slb.isIgnored) 0 else 1)
          .withPolicy(getBreakBeforeElsePolicy(owner))
        val mlSplitOpt = OptionalBraces
          .indentAndBreakBeforeCtrl[T.KwThen](owner.cond, mlSplitBase)
        Seq(slb, mlSplitOpt.getOrElse(mlSplitBase))
      case FormatToken(_: T.KwWhile | _: T.KwFor, right, _) =>
        def spaceMod = Space(style.spaces.isSpaceAfterKeyword(right))
        val splitBase = {
          val onlyNL = style.newlines.keepBreak(newlines)
          Split(if (onlyNL) Newline else spaceMod, 0)
        }
        val split = (formatToken.meta.leftOwner match {
          // block expr case is handled in OptionalBraces.WhileImpl
          case t: Term.While =>
            OptionalBraces.indentAndBreakBeforeCtrl[T.KwDo](t.expr, splitBase)
          // below, multi-enum cases are handled in OptionalBraces.ForImpl
          case Term.For(List(enum), _) =>
            OptionalBraces.indentAndBreakBeforeCtrl[T.KwDo](enum, splitBase)
          case Term.ForYield(List(enum), _) =>
            OptionalBraces.indentAndBreakBeforeCtrl[T.KwYield](enum, splitBase)
          case _ => None
        }).getOrElse(Split(spaceMod, 0))
        Seq(split)
      case FormatToken(close: T.RightParen, right, _) if (leftOwner match {
            case _: Term.If => !nextNonComment(formatToken).right.is[T.KwThen]
            case _: Term.ForYield => style.indentYieldKeyword
            case _: Term.While | _: Term.For =>
              !nextNonComment(formatToken).right.is[T.KwDo]
            case _ => false
          }) && !isFirstOrLastToken(close, leftOwner) =>
        val body = leftOwner match {
          case t: Term.If => t.thenp
          case t: Term.For => t.body
          case t: Term.ForYield => t.body
          case t: Term.While => t.body
        }
        val expire = getLastToken(body)
        def nlSplitFunc(cost: Int)(implicit l: sourcecode.Line) =
          Split(Newline, cost).withIndent(style.indent.main, expire, After)
        if (style.newlines.getBeforeMultiline eq Newlines.unfold)
          CtrlBodySplits.checkComment(formatToken, nlSplitFunc) { ft =>
            if (ft.right.is[T.LeftBrace]) {
              val nextFt = nextNonCommentSameLine(next(ft))
              val policy = decideNewlinesOnlyAfterToken(nextFt.left)
              Seq(Split(Space, 0, policy = policy))
            } else
              Seq(nlSplitFunc(0))
          }
        else
          CtrlBodySplits.get(formatToken, body) {
            Split(Space, 0).withSingleLineNoOptimal(
              expire,
              insideBracesBlock(formatToken, expire),
              noSyntaxNL = leftOwner.is[Term.ForYield] && right.is[T.KwYield]
            )
          }(nlSplitFunc)
      case FormatToken(T.RightBrace(), T.KwElse(), _) =>
        val nlOnly = style.newlines.alwaysBeforeElseAfterCurlyIf ||
          !leftOwner.is[Term.Block] || !leftOwner.parent.forall(_ == rightOwner)
        Seq(
          Split(Space.orNL(!nlOnly), 0)
        )

      case FormatToken(T.RightBrace(), T.KwYield(), _) =>
        Seq(
          Split(Space, 0)
        )
      case FormatToken(_, T.KwElse() | T.KwYield(), _) =>
        val expire = rhsOptimalToken(tokens.getLast(rightOwner))
        val noSpace = shouldBreak(formatToken)
        def exclude = insideBracesBlock(formatToken, expire)
        val noSyntaxNL = formatToken.right.is[T.KwYield]
        Seq(
          Split(Space, 0)
            .notIf(noSpace)
            .withSingleLineNoOptimal(expire, exclude, noSyntaxNL = noSyntaxNL),
          Split(Newline, 1)
        )
      case ft @ FormatToken(_, _: T.KwThen | _: T.KwDo, _) =>
        if (style.newlines.sourceIgnored || newlines == 0)
          Seq(
            Split(Space, 0)
              .withOptimalToken(nextNonCommentSameLine(next(ft)).left),
            Split(Newline, 1)
          )
        else
          Seq(Split(Newline, 0))
      // Last else branch
      case FormatToken(_: T.KwElse, _, _) if (leftOwner match {
            case t: Term.If => !t.elsep.is[Term.If]
            case x => throw new UnexpectedTree[Term.If](x)
          }) =>
        val body = leftOwner.asInstanceOf[Term.If].elsep
        val expire = getLastToken(body)
        def nlSplitFunc(cost: Int) =
          Split(Newline, cost).withIndent(style.indent.main, expire, After)
        if (style.newlines.getBeforeMultiline eq Newlines.unfold)
          Seq(nlSplitFunc(0))
        else
          CtrlBodySplits.get(formatToken, body) {
            Split(Space, 0).withSingleLineNoOptimal(expire)
          }(nlSplitFunc)

      // Type variance
      case tok @ FormatToken(T.Ident(_), T.Ident(_) | T.Underscore(), _)
          if isTypeVariant(leftOwner) =>
        Seq(Split(Space(isSymbolicIdent(tok.right)), 0))

      // Kind projector type lambda
      case FormatToken(T.Ident("+" | "-"), T.Underscore(), _)
          if leftOwner.is[Type.Name] =>
        Seq(Split(NoSplit, 0))

      // Var args
      case FormatToken(_, T.Ident("*"), _) if rightOwner.is[Type.Repeated] =>
        Seq(
          Split(NoSplit, 0)
        )

      case FormatToken(open: T.LeftParen, right, _) =>
        val isConfig = couldUseConfigStyle(formatToken)
        val close = matching(open)
        val enclosed = findEnclosedBetweenParens(open, close, leftOwner)
        def spaceSplitWithoutPolicy(implicit fileLine: FileLine) = {
          val indent: Length = right match {
            case T.KwIf() => StateColumn
            case T.KwFor() if !style.indentYieldKeyword => StateColumn
            case _ =>
              if (enclosed.exists(_.is[Term.ApplyInfix])) Num(0)
              else {
                val closeFt = tokens(close, -1)
                val willBreak = closeFt.left.is[T.Comment] &&
                  prevNonCommentSameLine(closeFt).hasBreak
                Num(if (willBreak) style.indent.main else 0)
              }
          }
          val useSpace = style.spaces.inParentheses
          Split(Space(useSpace), 0).withIndent(indent, close, Before)
        }
        def spaceSplit(implicit fileLine: FileLine) =
          spaceSplitWithoutPolicy.withPolicy(PenalizeAllNewlines(close, 1))
        def newlineSplit(
            cost: Int,
            forceDangle: Boolean
        )(implicit fileLine: FileLine) = {
          val shouldDangle = forceDangle || style.danglingParentheses.callSite
          Split(Newline, cost)
            .withPolicy(decideNewlinesOnlyBeforeClose(close), !shouldDangle)
            .withIndent(style.indent.callSite, close, Before)
        }
        if (tokens.isRightCommentThenBreak(formatToken))
          Seq(newlineSplit(0, isConfig))
        else
          style.newlines.source match {
            case Newlines.classic =>
              Seq(if (isConfig) newlineSplit(0, true) else spaceSplit)
            case Newlines.keep =>
              Seq(if (newlines != 0) newlineSplit(0, isConfig) else spaceSplit)
            case _ =>
              val singleLine = enclosed.forall { x =>
                style.newlines.source.eq(Newlines.unfold) &&
                x.parent.exists {
                  case _: Template | _: Defn => false
                  case InfixApp(_) => false
                  case _ => true
                }
              }
              Seq(
                if (!singleLine) spaceSplit
                else {
                  val singleLineInfixPolicy =
                    if (!enclosed.exists(isInfixApp)) None
                    else Some(getSingleLineInfixPolicy(close))
                  spaceSplitWithoutPolicy
                    .withSingleLine(close)
                    .andPolicyOpt(singleLineInfixPolicy)
                },
                newlineSplit(10, true)
              )
          }

      // Infix operator.
      case FormatToken(_: T.Ident, _, FormatToken.LeftOwner(AsInfixOp(app))) =>
        insideInfixSplit(app, formatToken)
      case FormatToken(_, _: T.Ident, FormatToken.RightOwner(AsInfixOp(app))) =>
        insideInfixSplit(app, formatToken)

      // Case
      case FormatToken(_: T.KwCase, _, _)
          if leftOwner.is[Defn.RepeatedEnumCase] =>
        val indent =
          Indent(style.indent.main, leftOwner.tokens.last, ExpiresOn.After)
        Seq(Split(Space, 0).withIndent(indent))

      case FormatToken(_: T.KwCase, _, _) if leftOwner.is[CaseTree] =>
        val owner = leftOwner.asInstanceOf[CaseTree]
        val body = owner.body
        val arrowFt = leftOwner match {
          case c: Case => getCaseArrow(c)
          case tc: TypeCase => getCaseArrow(tc)
        }
        val arrow = arrowFt.left
        val postArrowFt = nextNonCommentSameLine(arrowFt)
        val bodyEnd = tokens.getLastNonTrivialOpt(body)
        val expire = bodyEnd.fold(postArrowFt)(nextNonCommentSameLine).left

        val bodyBlock = isCaseBodyABlock(arrowFt, owner)
        def defaultPolicy = decideNewlinesOnlyAfterToken(postArrowFt.left)
        val policy =
          if (bodyBlock || tokens.isAttachedCommentThenBreak(arrowFt)) NoPolicy
          else if (isCaseBodyEnclosedAsBlock(postArrowFt, owner)) {
            val postParenFt = nextNonCommentSameLine(next(postArrowFt))
            val lparen = postParenFt.left
            val rparen = matching(lparen)
            if (postParenFt.right.start >= rparen.start) defaultPolicy
            else {
              val indent = style.indent.main
              val lindents = Seq(
                Indent(indent, rparen, Before),
                Indent(-indent, expire, After)
              )
              val lmod = NewlineT(noIndent = rhsIsCommentedOut(postParenFt))
              val lsplit = Seq(Split(lmod, 0).withIndents(lindents))
              val rsplit = Seq(Split(Newline, 0))
              val open = Policy.after(lparen) {
                case d: Decision if d.formatToken eq postParenFt => lsplit
              }
              val close = Policy.on(rparen) {
                case d: Decision if d.formatToken.right eq rparen => rsplit
              }
              new Policy.Relay(open, close)
            }
          } else if (
            style.newlines.getBeforeMultiline.in(Newlines.fold, Newlines.keep)
          ) NoPolicy
          else defaultPolicy

        val bodyIndent = if (bodyBlock) 0 else style.indent.main
        val arrowIndent = style.indent.caseSite - bodyIndent
        val indents = Seq(
          Indent(bodyIndent, expire, After),
          Indent(arrowIndent, arrow, After)
        )
        val mod = ModExt(Space, indents)
        Seq(
          Split(mod, 0).withSingleLine(expire, killOnFail = true),
          Split(mod, 0, policy = policy)
        )

      case tok @ FormatToken(_, cond @ T.KwIf(), _) if rightOwner.is[Case] =>
        if (style.newlines.keepBreak(newlines))
          Seq(Split(Newline, 0))
        else {
          val arrow = getCaseArrow(rightOwner.asInstanceOf[Case]).left
          val afterIf = nextNonCommentSameLine(next(tok))
          val noSplit =
            if (style.newlines.keepBreak(afterIf)) {
              val indent = Indent(style.indent.main, arrow, ExpiresOn.Before)
              Split(Space, 0).withSingleLine(afterIf.left).withIndent(indent)
            } else {
              val exclude = insideBracesBlock(tok, arrow)
              Split(Space, 0).withSingleLineNoOptimal(arrow, exclude = exclude)
            }
          Seq(
            noSplit,
            Split(Newline, 1).withPolicy(penalizeNewlineByNesting(cond, arrow))
          )
        }

      case ft @ FormatToken(_: T.KwIf, _, _) if leftOwner.is[Case] =>
        val useNL = style.newlines.keepBreak(nextNonCommentSameLine(ft))
        Seq(Split(Space.orNL(!useNL), 0))

      // ForYield
      case tok @ FormatToken(_: T.LeftArrow, _, _)
          if leftOwner.is[Enumerator.Generator] =>
        val enumerator = leftOwner.asInstanceOf[Enumerator.Generator]
        getSplitsEnumerator(tok, enumerator.rhs)
      case tok @ FormatToken(_: T.Equals, _, _)
          if leftOwner.is[Enumerator.Val] =>
        val enumerator = leftOwner.asInstanceOf[Enumerator.Val]
        getSplitsEnumerator(tok, enumerator.rhs)

      case FormatToken(_: T.KwTry | _: T.KwCatch | _: T.KwFinally, _, _) =>
        val body = formatToken.meta.leftOwner match {
          case t: Term.Try =>
            formatToken.left match {
              case _: T.KwTry => t.expr
              case _: T.KwCatch => t
              case _: T.KwFinally => t.finallyp.getOrElse(t)
              case _ => t
            }
          case t: Term.TryWithHandler =>
            formatToken.left match {
              case _: T.KwTry => t.expr
              case _: T.KwCatch => t.catchp
              case _: T.KwFinally => t.finallyp.getOrElse(t)
              case _ => t
            }
          case t => t
        }
        val end = getLastToken(body)
        val indent = Indent(style.indent.main, end, ExpiresOn.After)
        CtrlBodySplits.get(formatToken, body, Seq(indent)) {
          Split(Space, 0).withSingleLineNoOptimal(end)
        }(Split(Newline, _).withIndent(indent))

      // Term.ForYield
      case FormatToken(T.KwYield(), _, _) if leftOwner.is[Term.ForYield] =>
        val lastToken =
          getLastToken(leftOwner.asInstanceOf[Term.ForYield].body)
        val indent = Indent(style.indent.main, lastToken, ExpiresOn.After)
        if (style.newlines.avoidAfterYield && !rightOwner.is[Term.If]) {
          val nextFt = nextNonCommentSameLine(formatToken)
          val noIndent = nextFt.eq(formatToken) || nextFt.noBreak
          Seq(Split(Space, 0).withIndent(indent, noIndent))
        } else {
          Seq(
            // Either everything fits in one line or break on =>
            Split(Space, 0).withSingleLineNoOptimal(lastToken),
            Split(Newline, 1).withIndent(indent)
          )
        }

      // Term.For
      case ft @ FormatToken(rb: T.RightBrace, _, _)
          if leftOwner.is[Term.For] && !isLastToken(rb, leftOwner) &&
            !nextNonComment(formatToken).right.is[T.KwDo] =>
        val body = leftOwner.asInstanceOf[Term.For].body
        def nlSplit(cost: Int) = Split(Newline, cost)
          .withIndent(style.indent.main, getLastToken(body), After)
        CtrlBodySplits.get(ft, body)(null)(nlSplit)

      // After comment
      case FormatToken(_: T.Comment, _, _) =>
        Seq(Split(getMod(formatToken), 0))
      // Inline comment
      case FormatToken(_, _: T.Comment, _) =>
        val forceBlankLine = formatToken.hasBreak &&
          blankLineBeforeDocstring(formatToken)
        val mod = if (forceBlankLine) Newline2x else getMod(formatToken)
        val baseSplit = Split(mod, 0)
        formatToken.meta.rightOwner match {
          case GetSelectLike(t) =>
            val expire = nextNonComment(next(formatToken)).left
            val indent = Indent(style.indent.main, expire, ExpiresOn.After)
            val split = baseSplit.withIndent(indent)
            if (findPrevSelect(t, style.encloseSelectChains).isEmpty) Seq(split)
            else Seq(baseSplit, split.onlyFor(SplitTag.SelectChainFirstNL))
          case _ => Seq(baseSplit)
        }

      case FormatToken(soft.ImplicitOrUsing(), _, _)
          if style.binPack.unsafeDefnSite.isNever &&
            !style.verticalMultiline.atDefnSite &&
            isRightImplicitOrUsingSoftKw(formatToken, soft) =>
        val argsOrParamsOpt = formatToken.meta.leftOwner match {
          // for the using argument list
          case _: ApplyUsing => Some(getApplyArgs(formatToken, false).args)
          case _ => opensImplicitParamList(prevNonCommentBefore(formatToken))
        }
        argsOrParamsOpt.fold {
          Seq(Split(Space, 0))
        } { params =>
          val spaceSplit = Split(Space, 0)
            .notIf(style.newlines.forceAfterImplicitParamListModifier)
            .withPolicy(
              SingleLineBlock(params.last.tokens.last),
              style.newlines.notPreferAfterImplicitParamListModifier
            )
          Seq(
            spaceSplit,
            Split(Newline, if (spaceSplit.isActive) 1 else 0)
          )
        }

      case FormatToken(_, r, _) if optionalNewlines(hash(r)) =>
        def noAnnoLeft =
          leftOwner.is[Mod] ||
            !leftOwner.parent.exists(_.parent.exists(_.is[Mod.Annot]))
        def newlineOrBoth = {
          val spaceOk = !style.optIn.annotationNewlines
          Seq(Split(Space.orNL(spaceOk), 0), Split(Newline, 1).onlyIf(spaceOk))
        }
        style.newlines.source match {
          case _ if formatToken.hasBlankLine => Seq(Split(Newline2x, 0))
          case Newlines.unfold =>
            if (r.is[T.At]) newlineOrBoth
            else Seq(Split(Space.orNL(noAnnoLeft), 0))
          case Newlines.fold =>
            if (r.is[T.At]) Seq(Split(Space, 0), Split(Newline, 1))
            else if (noAnnoLeft) Seq(Split(Space, 0))
            else newlineOrBoth
          case _ =>
            val noNL = !style.optIn.annotationNewlines || formatToken.noBreak
            Seq(Split(Space.orNL(noNL), 0))
        }

      // Pattern alternatives
      case FormatToken(T.Ident("|"), _, _) if leftOwner.is[Pat.Alternative] =>
        if (style.newlines.source eq Newlines.keep)
          Seq(Split(Space.orNL(newlines == 0), 0))
        else
          Seq(Split(Space, 0), Split(Newline, 1))
      case FormatToken(_, T.Ident("|"), _) if rightOwner.is[Pat.Alternative] =>
        val noNL = !style.newlines.keepBreak(newlines)
        Seq(Split(Space.orNL(noNL), 0))
      case FormatToken(_, T.Ident("*"), _)
          if rightOwner.is[Pat.SeqWildcard] ||
            rightOwner.is[Term.Repeated] || rightOwner.is[Pat.Repeated] =>
        Seq(
          Split(NoSplit, 0)
        )
      case FormatToken(
            T.Ident(_) | Literal() | T.Interpolation.End() | T.Xml.End(),
            T.Ident(_) | Literal() | T.Xml.Start(),
            _
          ) =>
        Seq(
          Split(Space, 0)
        )

      // Case
      case FormatToken(left, _: T.KwMatch, _) =>
        // do not split `.match`
        val noSplit = left.is[T.Dot] && dialect.allowMatchAsOperator
        Seq(Split(Space(!noSplit), 0))

      // Protected []
      case FormatToken(_, T.LeftBracket(), _)
          if isModPrivateProtected(leftOwner) =>
        Seq(
          Split(NoSplit, 0)
        )
      case FormatToken(T.LeftBracket(), _, _)
          if isModPrivateProtected(leftOwner) =>
        Seq(
          Split(NoSplit, 0)
        )

      // Term.ForYield
      case FormatToken(_, _: T.KwIf, _) if rightOwner.is[Enumerator.Guard] =>
        /* this covers the first guard only; second and later consecutive guards
         * are considered to start a new statement and are handled early, in the
         * "if startsStatement" matches */
        style.newlines.source match {
          case Newlines.fold =>
            val endOfGuard = getLastToken(rightOwner)
            val exclude =
              insideBracesBlock(formatToken, endOfGuard, true)
            Seq(
              Split(Space, 0).withSingleLine(endOfGuard, exclude = exclude),
              Split(Newline, 1)
            )
          case Newlines.unfold =>
            Seq(Split(Newline, 0))
          case _ =>
            Seq(
              Split(Space, 0).onlyIf(newlines == 0),
              Split(Newline, 1)
            )
        }
      // Interpolation
      case FormatToken(_, _: T.Interpolation.Id, _) =>
        Seq(
          Split(Space, 0)
        )
      // Throw exception
      case FormatToken(T.KwThrow(), _, _) =>
        Seq(
          Split(Space, 0)
        )

      // Singleton types
      case FormatToken(_, T.KwType(), _) if rightOwner.is[Type.Singleton] =>
        Seq(
          Split(NoSplit, 0)
        )
      // seq to var args foo(seq:_*)
      case FormatToken(T.Colon(), T.Underscore(), _)
          if next(formatToken).meta.right.text == "*" =>
        Seq(
          Split(Space, 0)
        )
      case FormatToken(T.Underscore(), T.Ident("*"), _)
          if prev(formatToken).left.is[T.Colon] =>
        Seq(
          Split(NoSplit, 0)
        )

      // Xml
      case FormatToken(_, _: T.Xml.Start, _) =>
        Seq(
          Split(Space, 0)
        )
      case FormatToken(open: T.Xml.Start, _, _) =>
        val splits = Seq(Split(NoSplit, 0))
        if (prev(formatToken).left.is[T.LeftBrace])
          splits
        else
          withIndentOnXmlStart(open, splits)
      case FormatToken(_: T.Xml.SpliceStart, _, _)
          if style.xmlLiterals.assumeFormatted =>
        withIndentOnXmlSpliceStart(
          formatToken,
          Seq(Split(NoSplit, 0))
        )
      case FormatToken(T.Xml.Part(_), _, _) =>
        Seq(
          Split(NoSplit, 0)
        )
      case FormatToken(_, T.Xml.Part(_), _) =>
        Seq(
          Split(NoSplit, 0)
        )

      // Fallback
      case FormatToken(_, T.Dot(), _) =>
        Seq(
          Split(NoSplit, 0)
        )
      case FormatToken(left, T.Hash(), _) =>
        Seq(
          Split(Space(endsWithSymbolIdent(left)), 0)
        )
      case FormatToken(T.Hash(), ident: T.Ident, _) =>
        Seq(
          Split(Space(isSymbolicIdent(ident)), 0)
        )
      case FormatToken(T.Dot(), T.Ident(_) | T.KwThis() | T.KwSuper(), _) =>
        Seq(
          Split(NoSplit, 0)
        )
      case FormatToken(_, T.RightBracket(), _) =>
        Seq(
          Split(NoSplit, 0)
        )
      case FormatToken(_, close: T.RightParen, _) =>
        def modNoNL = {
          def allowSpace = rightOwner match {
            case _: Term.If | _: Term.While | _: Term.For | _: Term.ForYield =>
              isLastToken(close, rightOwner)
            case _ => true
          }
          Space(style.spaces.inParentheses && allowSpace)
        }
        val isNL = rightOwner.is[Pat.Alternative] &&
          style.newlines.keepBreak(newlines)
        Seq(Split(if (isNL) Newline else modNoNL, 0))

      case FormatToken(left, _: T.KwCatch | _: T.KwFinally, _)
          if style.newlines.alwaysBeforeElseAfterCurlyIf
            || !left.is[T.RightBrace] ||
            !(leftOwner.eq(rightOwner) ||
              leftOwner.is[Term.Block] &&
              leftOwner.parent.contains(rightOwner)) =>
        Seq(
          Split(NewlineT(formatToken.hasBlankLine), 0)
        )

      case FormatToken(_, Keyword(), _) =>
        Seq(
          Split(Space, 0)
        )

      case FormatToken(Keyword() | Modifier(), _, _) =>
        Seq(
          Split(Space, 0)
        )
      case FormatToken(T.LeftBracket(), _, _) =>
        Seq(
          Split(NoSplit, 0)
        )
      case FormatToken(_, Delim(), _) =>
        Seq(
          Split(Space, 0)
        )
      case FormatToken(T.Underscore(), T.Ident("*"), _) =>
        Seq(
          Split(NoSplit, 0)
        )
      case FormatToken(T.RightArrow(), _, _) if leftOwner.is[Type.ByName] =>
        val mod = Space(style.spaces.inByNameTypes)
        Seq(
          Split(mod, 0)
        )
      case FormatToken(Delim(), _, _) =>
        Seq(
          Split(Space, 0)
        )
      case tok =>
        logger.debug("MISSING CASE:\n" + log(tok))
        Seq() // No solution available, partially format tree.
    }
  }

  /** Assigns possible splits to a FormatToken.
    *
    * The FormatToken can be considered as a node in a graph and the splits as
    * edges. Given a format token (a node in the graph), Route determines which
    * edges lead out from the format token.
    */
  def getSplits(formatToken: FormatToken): Seq[Split] = {
    val splits = getSplitsImpl(formatToken).filter(!_.isIgnored)
    def splitsAsNewlines(splits: Seq[Split]): Seq[Split] = {
      val filtered = Decision.onlyNewlineSplits(splits)
      if (filtered.nonEmpty) filtered else splits.map(_.withMod(Newline))
    }
    formatToken match {
      case FormatToken(_: T.BOF, _, _) => splits
      case ft @ FormatToken(_, _: T.Comment, _)
          if tokens.isBreakAfterRight(ft) =>
        if (ft.noBreak) splits.map(_.withMod(Space))
        else if (!rhsIsCommentedOut(ft)) splitsAsNewlines(splits)
        else splitsAsNewlines(splits.map(_.withNoIndent))
      // Only newlines after inline comments.
      case ft @ FormatToken(_: T.Comment, _, _) if ft.hasBreak =>
        splitsAsNewlines(splits)
      case ft if ft.meta.formatOff && ft.hasBreak => splitsAsNewlines(splits)
      case FormatToken(_: T.Equals, r: T.KwMacro, _)
          if dialect.allowSignificantIndentation =>
        // scala3 compiler doesn't allow newline before `macro`
        splits.map { s =>
          if (!s.isNL) s
          else s.withMod(Space).andPolicy(decideNewlinesOnlyAfterClose(r))
        }
      case _ => splits
    }
  }

  private implicit def int2num(n: Int): Num = Num(n)

  private def getSplitsDefValEquals(
      ft: FormatToken,
      body: Tree,
      spaceIndents: Seq[Indent] = Seq.empty
  )(splits: => Seq[Split])(implicit
      style: ScalafmtConfig
  ): Seq[Split] = {
    def expire = getLastToken(body)
    if (ft.right.is[T.LeftBrace]) // The block will take care of indenting by 2
      Seq(Split(Space, 0).withIndents(spaceIndents))
    else if (
      ft.right.is[T.Comment] &&
      (ft.hasBreak || nextNonCommentSameLine(next(ft)).hasBreak)
    )
      Seq(CtrlBodySplits.withIndent(Split(Space.orNL(ft.noBreak), 0), ft, body))
    else if (isJsNative(body))
      Seq(Split(Space, 0).withSingleLine(expire))
    else if (style.newlines.shouldForceBeforeMultilineAssign(ft.meta.leftOwner))
      CtrlBodySplits.slbOnly(ft, body, spaceIndents) { x =>
        CtrlBodySplits.withIndent(Split(Newline, x), ft, body)
      }
    else
      splits
  }

  private def getSplitsDefEquals(ft: FormatToken, body: Tree)(implicit
      style: ScalafmtConfig
  ): Seq[Split] = {
    val expire = getLastToken(body)
    def baseSplit = Split(Space, 0)
    def newlineSplit(cost: Int)(implicit fileLine: FileLine) =
      CtrlBodySplits.withIndent(Split(Newline, cost), ft, body)

    def getClassicSplits =
      if (ft.hasBreak) Seq(newlineSplit(0))
      else Seq(baseSplit, newlineSplit(1))

    style.newlines.beforeMultilineDef.fold {
      getSplitsValEquals(ft, body)(getClassicSplits)
    } {
      case Newlines.classic => getClassicSplits

      case Newlines.keep if ft.hasBreak => Seq(newlineSplit(0))

      case Newlines.unfold =>
        Seq(baseSplit.withSingleLine(expire), newlineSplit(1))

      case x =>
        CtrlBodySplits.folded(ft, body, x eq Newlines.keep)(newlineSplit)
    }
  }

  private def getSplitsValEquals(ft: FormatToken, body: Tree)(
      classicSplits: => Seq[Split]
  )(implicit style: ScalafmtConfig): Seq[Split] =
    if (style.newlines.getBeforeMultiline eq Newlines.classic) classicSplits
    else CtrlBodySplits.getWithIndent(ft, body)(null)(Split(Newline, _))

  private def getSplitsValEqualsClassic(ft: FormatToken, body: Tree)(implicit
      style: ScalafmtConfig
  ): Seq[Split] = {
    def wouldDangle =
      ft.meta.leftOwner.parent.exists { lop =>
        if (isDefnSite(lop)) !shouldNotDangleAtDefnSite(lop, false)
        else
          isCallSite(lop) &&
          style.danglingParentheses.tupleOrCallSite(isTuple(lop))
      }

    val expireFt = tokens.getLast(body)
    val expire = expireFt.left
    // rhsOptimalToken is too aggressive here
    val optimalFt = expireFt.right match {
      case _: T.Comma => next(expireFt)
      case RightParenOrBracket() if !wouldDangle => next(expireFt)
      case _ => expireFt
    }
    val optimal = optimalFt.left
    def optimalWithComment =
      optimalFt.right match {
        case x: T.Comment if optimalFt.noBreak => x
        case _ => optimalFt.left
      }

    val penalty = ft.meta.leftOwner match {
      case _: Term.Assign if !style.binPack.unsafeCallSite.isNever =>
        Constants.BinPackAssignmentPenalty
      case _: Term.Param if !style.binPack.unsafeDefnSite.isNever =>
        Constants.BinPackAssignmentPenalty
      case _ => 0
    }

    def baseSpaceSplit(implicit fileLine: FileLine) =
      Split(tokens.isRightCommentThenBreak(ft), 0)(Space)
    def twoBranches(implicit fileLine: FileLine) =
      baseSpaceSplit
        .withOptimalToken(optimal)
        .withPolicy {
          val exclude = insideBracesBlock(ft, expire)
          policyWithExclude(exclude, Policy.End.On, Policy.End.After)(
            Policy.End.Before(expire),
            new PenalizeAllNewlines(_, Constants.ShouldBeSingleLine)
          )
        }
    val spaceSplit = body match {
      case _ if ft.hasBreak && ft.meta.leftOwner.is[Defn] => Split.ignored
      case _: Term.If => twoBranches
      case _: Term.ForYield => twoBranches
      // we force newlines in try/catch/finally
      case _: Term.Try | _: Term.TryWithHandler => Split.ignored
      case t: Term.Apply if t.args.nonEmpty =>
        baseSpaceSplit.withOptimalToken(optimalWithComment)
      case _ =>
        baseSpaceSplit.withOptimalToken(optimal)
    }
    Seq(
      spaceSplit,
      CtrlBodySplits.withIndent(Split(Newline, 1 + penalty), ft, body)
    )
  }

  private def getSplitsEnumerator(
      ft: FormatToken,
      body: Tree
  )(implicit style: ScalafmtConfig): Seq[Split] =
    maybeGetInfixSplitsBeforeLhs(ft) {
      val expire = getLastNonTrivialToken(body)
      val spaceIndents =
        if (!style.align.arrowEnumeratorGenerator) Seq.empty
        else Seq(Indent(StateColumn, expire, After))
      getSplitsDefValEquals(ft, body, spaceIndents) {
        CtrlBodySplits.get(ft, body, spaceIndents) {
          if (spaceIndents.nonEmpty)
            Split(Space, 0).withIndents(spaceIndents)
          else {
            val noSlb = body match {
              case _: Term.Try | _: Term.TryWithHandler => false
              case t: Term.If => ifWithoutElse(t)
              case _ => true
            }
            if (noSlb) Split(Space, 0).withOptimalToken(ft.right)
            else Split(Space, 0).withSingleLine(expire)
          }
        }(Split(Newline, _).withIndent(style.indent.main, expire, After))
      }
    }

}
