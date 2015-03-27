package fiddle
import acyclic.file
import scala.scalajs.js
import scala.scalajs.js.Dynamic.{literal => lit, _}
import org.scalajs.dom
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.runNow
import scala.async.Async.{async, await}
import scalatags.JsDom.all._
import scala.scalajs.js.annotation.JSExport
import org.scalajs.dom.ext.{AjaxException, Ajax}
import Page._
import JsVal.jsVal2jsAny
import Client.RedLogger
import scala.Some
import upickle._
import autowire._
@JSExport("Checker")
object Checker{
  /**
   * Deadline by which the user code must complete execution.
   */
  private[this] var endTime = 0.0
  /**
   * Switch to flip to once you have run out of time to make
   * `check` fail every single time, ensuring you get thrown out
   * of the user code
   */
  private[this] var dead = false
  /**
   * Used to avoid doing an expensive `currentTimeMillis` check on every call,
   * and instead doing one every N calls.
   */
  private[this] var count = 0
  @JSExport
  def check(): Unit = {
    count += 1
    if (count % 1000 == 0 && js.Date.now() > endTime || dead) {
      dead = true
      Client.clearTimeouts()
      js.eval("""throw new Error("Time's Up! Your code took too long to run.")""")
    }
  }

  @JSExport
  def reset(max: Int) = {
    count = 0
    dead = false
    endTime = math.max(js.Date.now() + max, endTime)
  }

  def scheduleResets() = {
    dom.setInterval(() => Checker.reset(1000), 100)
  }
}

object Post extends autowire.Client[String, upickle.Reader, upickle.Writer]{
  override def doCall(req: Request): Future[String] = {
    val url = "/api/" + req.path.mkString("/")
    logln("Calling " + url)
    Ajax.post(
      url = Shared.url + url,
      data = upickle.write(req.args)
    ).map(_.responseText)
  }
  def read[Result: upickle.Reader](p: String) = upickle.read[Result](p)
  def write[Result: upickle.Writer](r: Result) = upickle.write(r)
}

class Client(){

  Client.scheduleResets()
  val command = Channel[Future[(String, Option[String])]]()

  def exec(s: String) = {
    Client.clear()
    Client.scheduleResets()

    Checker.reset(1000)
    try{
      js.eval(s)
      js.eval("ScalaJSExample().main()")

    }catch{case e: Throwable =>
      Client.logError(e.getStackTraceString)
      Client.logError(e.toString())
    }
  }
  val instrument = "c"

  val compilationLoop = task*async{
    val future = await(command())
    await(compile(future)).foreach(exec)

    while(true){
      val future = await(command())

      val compiled = await(compile(future))
      compiled.foreach(exec)
    }
  }

  val editor: Editor = new Editor(Seq(
    ("Compile", "Enter", () => command.update(Post[Api].fastOpt(editor.code).call())),
    ("FullOptimize", "Shift-Enter", () => command.update(Post[Api].fullOpt(editor.code).call())),
    ("Save", "S", save _),
    ("Complete", "Space", () => editor.complete()),
    ("FastOptimizeJavascript", "J", () => showJavascript(Post[Api].fastOpt(editor.code).call())),
    ("FullOptimizedJavascript", "Shift-J", () => showJavascript(Post[Api].fullOpt(editor.code).call())),
    ("Export", "E", export _)
  ), complete, RedLogger)

  logln("- ", yellow("Cmd/Ctrl-Enter"), " to compile & execute, ", yellow("Cmd/Ctrl-Space"), " for autocomplete.")
  val landing = fiddle.Shared.url + "/gist/" + fiddle.Shared.gistId + "/LandingPage.scala"
  logln("- ", a(href:=landing, "Click here"), " to find out more.")

  def compile(res: Future[(String, Option[String])]): Future[Option[String]] = {
    res.map { case (logspam, result) =>


      logln(logspam)
      result match{
        case Some(c) =>
          log(green("Success"))
          logln()
        case None =>
          log(red("Failure"))
          logln()
      }
      result
    }.recover { case e: Exception =>
      Client.logError(e.getStackTraceString)
      Client.logError(e.toString)
      None
    }
  }

  def showJavascript(compiled: Future[(String, Option[String])]) = {
    compiled.collect{ case (logspam, Some(code)) =>
      Client.clear()
      Page.output.innerHTML = Page.highlight(code, "ace/mode/javascript")
    }
  }

  def complete() = async {
    log("Completing... ")

    val code = editor.sess.getValue().asInstanceOf[String]

    val intOffset = editor.column + code.split("\n")
                                        .take(editor.row)
                                        .map(_.length + 1)
                                        .sum

    val flag = if(code.take(intOffset).endsWith(".")) "member" else "scope"


    val res = await(Post[Api].completeStuff(code, flag, intOffset).call())
    log("Done")
    logln()
    res
  }

  def export(): Unit = task*async {
    logln("Exporting...")
    await(compile(Post[Api].fullOpt(editor.code).call())).foreach{ code =>
      Util.Form.post("/export",
        "source" -> editor.code,
        "compiled" -> code
      )
    }
  }

  def save(): Unit = task*async{
    await(compile(Post[Api].fullOpt(editor.code).call()))
    val data = JsVal.obj(
      "description" -> "Scala.jsFiddle gist",
      "public" -> true,
      "files" -> JsVal.obj(
        "Main.scala" -> JsVal.obj(
          "content" -> editor.code
        )
      )
    ).toString()

    val res = await(Ajax.post("https://api.github.com/gists", data = data))
    val result = JsVal.parse(res.responseText)
    Util.Form.get("/gist/" + result("id").asString)
  }
}

@JSExport("Client")
object Client{
  implicit val RedLogger = new Logger(logError)

  dom.onerror = {(event: dom.Event, source: String, fileno: Int, columnNumber: Int) =>
    dom.console.log("dom.onerror")
    Client.logError(event.toString())
  }


  @JSExport
  def logError(s: String): Unit = {
    logln(red(s))
  }
  @JSExport
  def clearTimeouts() = {
    for(i <- -100000 until 100000){
      dom.clearInterval(i)
      dom.clearTimeout(i)
    }
    Client.scheduleResets()
  }
  def clear() = {
    clearTimeouts()
    Page.clear()
  }

  @JSExport
  def gistMain(args: js.Array[String]): Unit = task*async{
    dom.console.log("gistMain")
    Editor.initEditor
    val (gistId, fileName) = args.toSeq match{
      case Nil => (fiddle.Shared.gistId, Some("Oscilloscope.scala"))
      case Seq(g) => (g, None)
      case Seq(g, f) => (g, Some(f))
    }

    val src = await(load(gistId, fileName))
    val client = new Client()
    client.editor.sess.setValue(src)

    client.command.update(Post[Api].fullOpt(src).call())
  }

  @JSExport
  def importMain(): Unit = {
    clear()
    val client = new Client()
  }

  def load(gistId: String, file: Option[String]): Future[String] = {
    val gistUrl = "https://gist.github.com/" + gistId
    logln(
      "Loading ",
      file.fold(span)(s => span(
        a(href := gistUrl + "#file-" + s.toLowerCase.replace('.', '-'))(s),
        " from "
      )),
      a(href := gistUrl)(gistUrl),
      "..."
    )
    Ajax.get("https://api.github.com/gists/" + gistId).map{ res =>
      val result = JsVal.parse(res.responseText)
      val mainFile = result("files").get(file.getOrElse(""))
      val firstFile = result("files").values(0)
      mainFile.getOrElse(firstFile)("content").asString
    }.recover{case e => fiddle.Shared.default}

  }
  def scheduleResets() = {
    dom.setInterval(() => Checker.reset(1000), 100)
  }
}
