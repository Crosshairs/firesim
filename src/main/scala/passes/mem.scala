package strober
package passes

import firrtl._
import firrtl.Utils.error
import scala.collection.mutable.ArrayBuffer

case class MemConf(
  name: String, 
  depth: BigInt,
  width: BigInt,
  readers: Seq[String],
  writers: Seq[String],
  readwriters: Seq[String],
  maskGran: BigInt)

object MemConfReader {
  sealed trait ConfField
  case object Name extends ConfField
  case object Depth extends ConfField
  case object Width extends ConfField
  case object Ports extends ConfField
  case object MaskGran extends ConfField
  type ConfFieldMap = Map[ConfField, String]
  // Read a conf file generated by [[firrtl.passes.ReplSeqMems]] 
  def apply(conf: java.io.File): Seq[MemConf] = {
    def parse(map: ConfFieldMap, list: List[String]): ConfFieldMap = list match {
      case Nil => map
      case "name" :: value :: tail => parse(map + (Name -> value), tail)
      case "depth" :: value :: tail => parse(map + (Depth -> value), tail)
      case "width" :: value :: tail => parse(map + (Width -> value), tail)
      case "ports" :: value :: tail => parse(map + (Ports -> value), tail)
      case "mask_gran" :: value :: tail => parse(map + (MaskGran -> value), tail)
      case field :: tail => error("Unknown field " + field)
    }
    io.Source.fromFile(conf).getLines.toSeq map { line =>
      val map = parse(Map[ConfField, String](), (line split " ").toList)
      val ports = map(Ports) split ","
      MemConf(map(Name), BigInt(map(Depth)), BigInt(map(Width)),
        ports filter (_ == "read"),
        ports filter (p => p == "write" || p == "mwrite"),
        ports filter (p => p == "rw" || p == "mrw"),
        map get MaskGran map (BigInt(_)) getOrElse (BigInt(0)))
    }
  }
}

class EmitMemFPGAVerilog(writer: java.io.Writer, conf: java.io.File) extends Transform {
  private val tab = " "
  private def emit(conf: MemConf) {
    val addrWidth = chisel3.util.log2Up(conf.depth) max 1
    val portdefs: Seq[Seq[String]] = (conf.readers.indices flatMap (i => Seq(
      Seq(tab, "input", s"R${i}_clk"),
      Seq(tab, s"input[${addrWidth-1}:0]", s"R${i}_addr"),
      Seq(tab, "input", s"R${i}_en"),
      Seq(tab, s"output[${conf.width-1}:0]", s"R${i}_data"))
    )) ++ (conf.writers.zipWithIndex flatMap { case (w, i) => Seq(
      Seq(tab, "input", s"W${i}_clk"),
      Seq(tab, s"input[${addrWidth-1}:0]", s"W${i}_addr"),
      Seq(tab, "input", s"W${i}_en"),
      Seq(tab, s"input[${conf.width-1}:0]", s"W${i}_data")) ++
      (if (w.head == 'm') Seq(Seq(tab, s"input[${conf.width/conf.maskGran-1}:0]", s"W${i}_mask")) else Nil)
    }) ++ (conf.readwriters.zipWithIndex flatMap { case (rw, i) => Seq(
      Seq(tab, "input", s"RW${i}_clk"),
      Seq(tab, s"input[${addrWidth-1}:0]", s"RW${i}_addr"),
      Seq(tab, "input", s"RW${i}_en"),
      Seq(tab, "input", s"RW${i}_wmode"),
      Seq(tab, s"input[${conf.width-1}:0]", s"RW${i}_wdata"),
      Seq(tab, s"output[${conf.width-1}:0]", s"RW${i}_rdata")) ++
      (if (rw.head == 'm') Seq(Seq(tab, s"input[${conf.width/conf.maskGran-1}:0]", s"RW${i}_wmask")) else Nil)
    })
    val declares = Seq(Seq(tab, s"reg[${conf.width-1}:0] ram[${conf.depth-1}:0];")) ++
      (conf.readers.indices map (i => Seq(tab, s"reg[${addrWidth-1}:0]", s"reg_R${i};"))) ++
      (conf.readwriters.indices map (i => Seq(tab, s"reg[${addrWidth-1}:0]", s"reg_RW${i};"))) ++
      Seq(Seq("`ifndef SYNTHESIS"),
          Seq(tab, "integer initvar;"),
          Seq(tab, "initial begin"),
          Seq(tab, tab, "#0.002;"),
          Seq(tab, tab, s"for (initvar = 0; initvar < ${conf.depth}; initvar = initvar + 1)"),
          Seq(tab, tab, tab, "ram[initvar] = {%d {$random}};".format((conf.width - 1) / 32 + 1))) ++
      (conf.readers.indices map (i =>
          Seq(tab, tab, "reg_R%d = {%d {$random}};".format(i, (addrWidth - 1) / 32 + 1)))) ++
      (conf.readwriters.indices map (i =>
          Seq(tab, tab, "reg_RW%d = {%d {$random}};".format(i, (addrWidth - 1) / 32 + 1)))) ++
      Seq(Seq(tab, "end"),
          Seq("`endif"))
    val assigns =
      (conf.readers.indices map (i => Seq(tab, "assign", s"R${i}_data = ram[reg_R${i}];"))) ++
      (conf.readwriters.indices map (i => Seq(tab, "assign", s"RW${i}_rdata = ram[reg_RW${i}];")))
    val always =
      (conf.readers.indices map (i => s"R${i}_clk" ->
        Seq(Seq(tab, tab, s"if (R${i}_en)", s"reg_R${i} <= R${i}_addr;")))) ++
      (conf.writers.zipWithIndex map { case (w, i) => s"W${i}_clk" -> (
        w.head == 'm' match {
          case false => Seq(
            Seq(tab, tab, s"if (W${i}_en)", s"ram[W${i}_addr] <= W${i}_data;"))
          case true => (0 until (conf.width / conf.maskGran).toInt) map { maskIdx =>
            val range = s"${(maskIdx + 1) * conf.maskGran - 1} : ${maskIdx * conf.maskGran}"
            Seq(tab, tab, s"if (W${i}_en && W${i}_wmode && W${i}_mask[${maskIdx}])",
                          s"ram[W${i}_addr][$range] <= W${i}_data[$range];")
          }
        }
      )}) ++ (conf.readwriters.zipWithIndex map { case (w, i) => s"RW${i}_clk" -> (
        Seq(tab, tab, s"if (RW${i}_en && ~RW${i}_wmode)",
                      s"reg_RW${i} <= RW${i}_addr;") +: (w.head == 'm' match {
          case false => Seq(
            Seq(tab, tab, s"if (RW${i}_en && RW${i}_wmode)",
                          s"ram[RW${i}_addr] <= RW${i}_wdata;"))
          case true => (0 until (conf.width / conf.maskGran).toInt) map { maskIdx =>
            val range = s"${(maskIdx + 1) * conf.maskGran - 1} : ${maskIdx * conf.maskGran}"
            Seq(tab, tab, s"if (RW${i}_en && RW${i}_wmode && RW${i}_wmask[${maskIdx}])",
                          s"ram[RW${i}_addr][$range] <= RW${i}_wdata[$range];")
          }
        })
      )})
    writer write s"""
module ${conf.name}(
%s
);
%s
%s
endmodule""".format(
     portdefs map (_ mkString " ") mkString ",\n",
     (declares ++ assigns) map (_ mkString " ") mkString "\n",
     always map { case (clk, body) => s"""
  always @(posedge $clk) begin
%s
  end""".format(body map (_ mkString " ") mkString "\n")
     } mkString "\n"
    )
  }

  def execute(c: ir.Circuit, map: Annotations.AnnotationMap) = {
    MemConfReader(conf) foreach emit
    TransformResult(c)
  }
}

// TODO: ASIC Verilog Emitter
