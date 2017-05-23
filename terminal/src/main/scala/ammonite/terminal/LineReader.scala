package ammonite.terminal



import scala.annotation.tailrec
import scala.collection.mutable

/**
  * Encapsulates the common configuration and logic around reading a single
  * line of input
  */
class LineReader(width: Int,
                 prompt: Prompt,
                 reader: java.io.Reader,
                 writer: java.io.Writer,
                 filters: Filter,
                 displayTransform: (Vector[Char], Int) => (fansi.Str, Int) = LineReader.noTransform) {

  lazy val ansi = new AnsiNav(writer)


  /**
    * Erases the previous line and re-draws it with the new buffer and
    * cursor.
    *
    * Relies on `ups` to know how "tall" the previous line was, to go up
    * and erase that many rows in the console. Performs a lot of horrific
    * math all over the place, incredibly prone to off-by-ones, in order
    * to at the end of the day position the cursor in the right spot.
    */
  def redrawLine(buffer: fansi.Str,
                 cursor: Int,
                 ups: Int,
                 rowLengths: Seq[Int],
                 fullPrompt: Boolean = true,
                 newlinePrompt: Boolean = false) = {

    val promptLine =
      if (fullPrompt) prompt.full
      else prompt.lastLine

    val promptWidth = if(newlinePrompt) 0 else prompt.lastLine.length
    val actualWidth = width - promptWidth

    ansi.up(ups)
    ansi.left(9999)
    ansi.clearScreen(0)
    writer.write(promptLine.toString)
    if (newlinePrompt) writer.write("\n")

    // I'm not sure why this is necessary, but it seems that without it, a
    // cursor that "barely" overshoots the end of a line, at the end of the
    // buffer, does not properly wrap and ends up dangling off the
    // right-edge of the terminal window!
    //
    // This causes problems later since the cursor is at the wrong X/Y,
    // confusing the rest of the math and ending up over-shooting on the
    // `ansi.up` calls, over-writing earlier lines. This prints a single
    // space such that instead of dangling it forces the cursor onto the
    // next line for-realz. If it isn't dangling the extra space is a no-op
    val lineStuffer = ' '
    // Under `newlinePrompt`, we print the thing almost-verbatim, since we
    // want to avoid breaking code by adding random indentation. If not, we
    // are guaranteed that the lines are short, so we can indent the newlines
    // without fear of wrapping
    val newlineReplacement =
    if (newlinePrompt) {

      Array(lineStuffer, '\n')
    } else {
      val indent = " " * prompt.lastLine.length
      Array('\n', indent:_*)
    }

    writer.write(
      buffer.render.flatMap{
        case '\n' => newlineReplacement
        case x => Array(x)
      }.toArray
    )
    writer.write(lineStuffer)

    val fragHeights = LineReader.calculateHeight0(rowLengths, actualWidth)
    val (cursorY, cursorX) = LineReader.positionCursor(
      cursor,
      rowLengths,
      fragHeights,
      actualWidth
    )
    ansi.up(fragHeights.sum - 1)
    ansi.left(9999)
    ansi.down(cursorY)
    ansi.right(cursorX)
    if (!newlinePrompt) ansi.right(prompt.lastLine.length)

    writer.flush()
  }

  @tailrec
  final def readChar(lastState: TermState,
                     ups: Int,
                     fullPrompt: Boolean = true): Option[String] = {
    val moreInputComing = reader.ready()

    lazy val (transformedBuffer0, cursorOffset) = displayTransform(
      lastState.buffer,
      lastState.cursor
    )

    lazy val transformedBuffer = transformedBuffer0 ++ lastState.msg

    lazy val lastOffsetCursor = lastState.cursor + cursorOffset

    lazy val rowLengths = LineReader.splitBuffer(lastState.buffer ++ lastState.msg.plainText)

    val narrowWidth = width - prompt.lastLine.length
    val newlinePrompt = rowLengths.exists(_ >= narrowWidth)
    val promptWidth = if(newlinePrompt) 0 else prompt.lastLine.length
    val actualWidth = width - promptWidth
    val newlineUp = if (newlinePrompt) 1 else 0

    // We don't actually print anything if more input is coming
    val oldCursorY =
      if (moreInputComing) ups
      else {
        redrawLine(
          transformedBuffer,
          lastOffsetCursor,
          ups,
          rowLengths,
          fullPrompt,
          newlinePrompt
        )
        LineReader.positionCursor(
          lastOffsetCursor,
          rowLengths,
          LineReader.calculateHeight0(rowLengths, actualWidth),
          actualWidth
        )._1
      }

    def updateState(s: LazyList[Int],
                    b: Vector[Char],
                    c: Int,
                    msg: fansi.Str): (Int, TermState) = {

      val newCursor = math.max(math.min(c, b.length), 0)
      val nextUps =
        if (moreInputComing) ups
        else oldCursorY + newlineUp

      val newState = TermState(s, b, newCursor, msg)

      (nextUps, newState)
    }
    // `.get` because we assume that *some* filter is going to match each
    // character, even if only to dump the character to the screen. If nobody
    // matches the character then we can feel free to blow up
    filters.op(TermInfo(lastState, actualWidth)).get match {
      case Printing(TermState(s, b, c, msg), stdout) =>
        writer.write(stdout)
        val (nextUps, newState) = updateState(s, b, c, msg)
        readChar(newState, nextUps)

      case TermState(s, b, c, msg) =>
        val (nextUps, newState) = updateState(s, b, c, msg)
        readChar(newState, nextUps, false)

      case Result(s) =>
        redrawLine(
          transformedBuffer, lastState.buffer.length,
          oldCursorY + newlineUp, rowLengths, false, newlinePrompt
        )
        writer.write(10)
        writer.write(13)
        writer.flush()
        Some(s)
      case ClearScreen(ts) =>
        ansi.clearScreen(2)
        ansi.up(9999)
        ansi.left(9999)
        readChar(ts, ups)
      case Exit =>
        None
    }
  }

}



object LineReader{

  /**
    * Computes how tall a line of text is when wrapped at `width`.
    *
    * Even 0-character lines still take up one row!
    *
    * width = 2
    * 0 -> 1
    * 1 -> 1
    * 2 -> 1
    * 3 -> 2
    * 4 -> 2
    * 5 -> 3
    */
  def fragHeight(length: Int, width: Int) = math.max(1, (length - 1) / width + 1)

  def splitBuffer(buffer: Vector[Char]) = {
    val frags = mutable.Buffer.empty[Int]
    frags.append(0)
    for(c <- buffer){
      if (c == '\n') frags.append(0)
      else frags(frags.length - 1) = frags.last + 1
    }
    frags
  }
  def calculateHeight(buffer: Vector[Char],
                      width: Int,
                      prompt: String): Seq[Int] = {
    val rowLengths = splitBuffer(buffer)

    calculateHeight0(rowLengths, width - prompt.length)
  }

  /**
    * Given a buffer with characters and newlines, calculates how high
    * the buffer is and where the cursor goes inside of it.
    */
  def calculateHeight0(rowLengths: Seq[Int],
                       width: Int): Seq[Int] = {
    val fragHeights =
      rowLengths
        .inits
        .toVector
        .reverse // We want shortest-to-longest, inits gives longest-to-shortest
        .filter(_.nonEmpty) // Without the first empty prefix
        .map{ x =>
        fragHeight(
          // If the frag barely fits on one line, give it
          // an extra spot for the cursor on the next line
          x.last + 1,
          width
        )
      }
    //    Debug("fragHeights " + fragHeights)
    fragHeights
  }

  def positionCursor(cursor: Int,
                     rowLengths: Seq[Int],
                     fragHeights: Seq[Int],
                     width: Int) = {
    var leftoverCursor = cursor
    //    Debug("leftoverCursor " + leftoverCursor)
    var totalPreHeight = 0
    var done = false
    // Don't check if the cursor exceeds the last chunk, because
    // even if it does there's nowhere else for it to go
    for(i <- 0 until rowLengths.length -1 if !done) {
      // length of frag and the '\n' after it
      val delta = rowLengths(i) + 1
      //      Debug("delta " + delta)
      val nextCursor = leftoverCursor - delta
      if (nextCursor >= 0) {
        //        Debug("nextCursor " + nextCursor)
        leftoverCursor = nextCursor
        totalPreHeight += fragHeights(i)
      }else done = true
    }

    val cursorY = totalPreHeight + leftoverCursor / width
    val cursorX = leftoverCursor % width

    (cursorY, cursorX)
  }
  def noTransform(x: Vector[Char], i: Int) = (fansi.Str(x), i)
}

case class Prompt(full: fansi.Str, lastLine: fansi.Str)

object Prompt {
  implicit def construct(prompt: String): Prompt = {
    val parsedPrompt = fansi.Str(prompt)
    val index = parsedPrompt.plainText.lastIndexOf('\n')
    val (_, last) = parsedPrompt.splitAt(index+1)
    Prompt(parsedPrompt, last)
  }
}

