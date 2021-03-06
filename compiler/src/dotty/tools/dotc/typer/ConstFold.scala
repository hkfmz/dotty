package dotty.tools.dotc
package typer

import java.lang.ArithmeticException

import ast._
import Trees._
import core._
import Types._
import Constants._
import Names._
import StdNames._
import Contexts._
import Nullables.{CompareNull, TrackedRef}

object ConstFold {

  import tpd._

  /** If tree is a constant operation, replace with result. */
  def apply[T <: Tree](tree: T)(implicit ctx: Context): T = finish(tree) {
    tree match {
      case CompareNull(TrackedRef(ref), testEqual)
      if ctx.settings.YexplicitNulls.value && ctx.notNullInfos.impliesNotNull(ref) =>
        // TODO maybe drop once we have general Nullability?
        Constant(!testEqual)
      case Apply(Select(xt, op), yt :: Nil) =>
        xt.tpe.widenTermRefExpr.normalized match
          case ConstantType(x) =>
            yt.tpe.widenTermRefExpr match
              case ConstantType(y) => foldBinop(op, x, y)
              case _ => null
          case _ => null
      case Select(xt, op) =>
        xt.tpe.widenTermRefExpr match {
          case ConstantType(x) => foldUnop(op, x)
          case _ => null
        }
      case _ => null
    }
  }

  /** If tree is a constant value that can be converted to type `pt`, perform
   *  the conversion.
   */
  def apply[T <: Tree](tree: T, pt: Type)(implicit ctx: Context): T =
    finish(apply(tree)) {
      tree.tpe.widenTermRefExpr.normalized match {
        case ConstantType(x) => x convertTo pt
        case _ => null
      }
    }

  inline private def finish[T <: Tree](tree: T)(compX: => Constant)(implicit ctx: Context): T =
    try {
      val x = compX
      if (x ne null) tree.withType(ConstantType(x)).asInstanceOf[T]
      else tree
    }
    catch {
      case _: ArithmeticException => tree   // the code will crash at runtime,
                                            // but that is better than the
                                            // compiler itself crashing
    }

  private def foldUnop(op: Name, x: Constant): Constant = (op, x.tag) match {
    case (nme.UNARY_!, BooleanTag) => Constant(!x.booleanValue)

    case (nme.UNARY_~ , IntTag    ) => Constant(~x.intValue)
    case (nme.UNARY_~ , LongTag   ) => Constant(~x.longValue)

    case (nme.UNARY_+ , IntTag    ) => Constant(x.intValue)
    case (nme.UNARY_+ , LongTag   ) => Constant(x.longValue)
    case (nme.UNARY_+ , FloatTag  ) => Constant(x.floatValue)
    case (nme.UNARY_+ , DoubleTag ) => Constant(x.doubleValue)

    case (nme.UNARY_- , IntTag    ) => Constant(-x.intValue)
    case (nme.UNARY_- , LongTag   ) => Constant(-x.longValue)
    case (nme.UNARY_- , FloatTag  ) => Constant(-x.floatValue)
    case (nme.UNARY_- , DoubleTag ) => Constant(-x.doubleValue)

    case _ => null
  }

  /** These are local helpers to keep foldBinop from overly taxing the
   *  optimizer.
   */
  private def foldBooleanOp(op: Name, x: Constant, y: Constant): Constant = op match {
    case nme.ZOR  => Constant(x.booleanValue | y.booleanValue)
    case nme.OR   => Constant(x.booleanValue | y.booleanValue)
    case nme.XOR  => Constant(x.booleanValue ^ y.booleanValue)
    case nme.ZAND => Constant(x.booleanValue & y.booleanValue)
    case nme.AND  => Constant(x.booleanValue & y.booleanValue)
    case nme.EQ   => Constant(x.booleanValue == y.booleanValue)
    case nme.NE   => Constant(x.booleanValue != y.booleanValue)
    case _ => null
  }
  private def foldSubrangeOp(op: Name, x: Constant, y: Constant): Constant = op match {
    case nme.OR  => Constant(x.intValue | y.intValue)
    case nme.XOR => Constant(x.intValue ^ y.intValue)
    case nme.AND => Constant(x.intValue & y.intValue)
    case nme.LSL => Constant(x.intValue << y.intValue)
    case nme.LSR => Constant(x.intValue >>> y.intValue)
    case nme.ASR => Constant(x.intValue >> y.intValue)
    case nme.EQ  => Constant(x.intValue == y.intValue)
    case nme.NE  => Constant(x.intValue != y.intValue)
    case nme.LT  => Constant(x.intValue < y.intValue)
    case nme.GT  => Constant(x.intValue > y.intValue)
    case nme.LE  => Constant(x.intValue <= y.intValue)
    case nme.GE  => Constant(x.intValue >= y.intValue)
    case nme.ADD => Constant(x.intValue + y.intValue)
    case nme.SUB => Constant(x.intValue - y.intValue)
    case nme.MUL => Constant(x.intValue * y.intValue)
    case nme.DIV => Constant(x.intValue / y.intValue)
    case nme.MOD => Constant(x.intValue % y.intValue)
    case _ => null
  }
  private def foldLongOp(op: Name, x: Constant, y: Constant): Constant = op match {
    case nme.OR  => Constant(x.longValue | y.longValue)
    case nme.XOR => Constant(x.longValue ^ y.longValue)
    case nme.AND => Constant(x.longValue & y.longValue)
    case nme.LSL => if (x.tag <= IntTag) Constant(x.intValue << y.longValue.toInt) else Constant(x.longValue << y.longValue)
    case nme.LSR => if (x.tag <= IntTag) Constant(x.intValue >>> y.longValue.toInt) else Constant(x.longValue >>> y.longValue)
    case nme.ASR => if (x.tag <= IntTag) Constant(x.intValue >> y.longValue.toInt) else Constant(x.longValue >> y.longValue)
    case nme.EQ  => Constant(x.longValue == y.longValue)
    case nme.NE  => Constant(x.longValue != y.longValue)
    case nme.LT  => Constant(x.longValue < y.longValue)
    case nme.GT  => Constant(x.longValue > y.longValue)
    case nme.LE  => Constant(x.longValue <= y.longValue)
    case nme.GE  => Constant(x.longValue >= y.longValue)
    case nme.ADD => Constant(x.longValue + y.longValue)
    case nme.SUB => Constant(x.longValue - y.longValue)
    case nme.MUL => Constant(x.longValue * y.longValue)
    case nme.DIV => Constant(x.longValue / y.longValue)
    case nme.MOD => Constant(x.longValue % y.longValue)
    case _ => null
  }
  private def foldFloatOp(op: Name, x: Constant, y: Constant): Constant = op match {
    case nme.EQ  => Constant(x.floatValue == y.floatValue)
    case nme.NE  => Constant(x.floatValue != y.floatValue)
    case nme.LT  => Constant(x.floatValue < y.floatValue)
    case nme.GT  => Constant(x.floatValue > y.floatValue)
    case nme.LE  => Constant(x.floatValue <= y.floatValue)
    case nme.GE  => Constant(x.floatValue >= y.floatValue)
    case nme.ADD => Constant(x.floatValue + y.floatValue)
    case nme.SUB => Constant(x.floatValue - y.floatValue)
    case nme.MUL => Constant(x.floatValue * y.floatValue)
    case nme.DIV => Constant(x.floatValue / y.floatValue)
    case nme.MOD => Constant(x.floatValue % y.floatValue)
    case _ => null
  }
  private def foldDoubleOp(op: Name, x: Constant, y: Constant): Constant = op match {
    case nme.EQ  => Constant(x.doubleValue == y.doubleValue)
    case nme.NE  => Constant(x.doubleValue != y.doubleValue)
    case nme.LT  => Constant(x.doubleValue < y.doubleValue)
    case nme.GT  => Constant(x.doubleValue > y.doubleValue)
    case nme.LE  => Constant(x.doubleValue <= y.doubleValue)
    case nme.GE  => Constant(x.doubleValue >= y.doubleValue)
    case nme.ADD => Constant(x.doubleValue + y.doubleValue)
    case nme.SUB => Constant(x.doubleValue - y.doubleValue)
    case nme.MUL => Constant(x.doubleValue * y.doubleValue)
    case nme.DIV => Constant(x.doubleValue / y.doubleValue)
    case nme.MOD => Constant(x.doubleValue % y.doubleValue)
    case _ => null
  }

  private def foldBinop(op: Name, x: Constant, y: Constant): Constant = {
    val optag =
      if (x.tag == y.tag) x.tag
      else if (x.isNumeric && y.isNumeric) math.max(x.tag, y.tag)
      else NoTag

    try optag match {
      case BooleanTag                               => foldBooleanOp(op, x, y)
      case ByteTag | ShortTag | CharTag | IntTag    => foldSubrangeOp(op, x, y)
      case LongTag                                  => foldLongOp(op, x, y)
      case FloatTag                                 => foldFloatOp(op, x, y)
      case DoubleTag                                => foldDoubleOp(op, x, y)
      case StringTag if op == nme.ADD               => Constant(x.stringValue + y.stringValue)
      case _                                        => null
    }
    catch {
      case ex: ArithmeticException => null
    }
  }
}
