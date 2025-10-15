package lila.jsBot

import reactivemongo.api.bson.*

import lila.db.{ BSON, JSON }
import lila.db.dsl.{ *, given }

final private class JsBotRepo(bots: Coll, assets: Coll)(using Executor):

  private given BSONDocumentHandler[BotJson] = new BSON[BotJson]:
    import play.api.libs.json.JsString
    def reads(r: BSON.Reader) = BotJson:
      JSON.jval(r.doc) - "_id" + ("key" -> JsString(r.str("uid").drop(1)))
    def writes(w: BSON.Writer, b: BotJson) = JSON.bdoc(b.value)

  private def $uid(uid: BotUid) = $doc("uid" -> uid)

  def getVersions(botId: Option[BotUid] = none): Fu[List[BotJson]] =
    bots
      .find(botId.so(v => $uid(v)))
      .sort($doc("version" -> -1))
      .cursor[BotJson]()
      .list(Int.MaxValue)

  def getLatestBots(): Fu[List[BotJson]] =
    bots
      .aggregateWith[BotJson](readPreference = ReadPref.sec): framework =>
        import framework.*
        List(
          // Match($doc("uid" -> "#centipawn")),
          Sort(Descending("version")),
          GroupField("uid")("doc" -> FirstField("$ROOT")),
          ReplaceRootField("doc")
        )
      .list(Int.MaxValue)

  def putBot(bot: BotJson, author: UserId): Fu[BotJson] = for
    fullBot <- bots.find($uid(bot.uid)).sort($doc("version" -> -1)).one[Bdoc]
    nextVersion = fullBot.flatMap(_.int("version")).getOrElse(-1) + 1 // race condition
    newBot = bot.withMeta(BotMeta(bot.uid, author, nextVersion, nowInstant))
    _ <- bots.insert.one(newBot)
  yield newBot

  def getAssets: Fu[Map[String, AssetName]] =
    assets
      .find($doc())
      .cursor[Bdoc]()
      .list(Int.MaxValue)
      .map: docs =>
        for
          doc <- docs
          id <- doc.getAsOpt[String]("_id")
          name <- doc.getAsOpt[AssetName]("name")
        yield id -> name
      .map(_.toMap)

  def nameAsset(tpe: Option[AssetType], key: AssetKey, name: AssetName, author: Option[UserId]): Funit =
    // filter out bookCovers as they share the same key as the book
    if !(tpe.has("book") && key.endsWith(".png")) then
      val id = if tpe.has("book") then key.dropRight(4) else key
      val setDoc = $doc("name" -> name) ++ author.so(a => $doc("author" -> a))
      assets.update.one($id(id), $doc("$set" -> setDoc), upsert = true).void
    else funit

  def deleteAsset(key: String): Funit =
    assets.delete.one($id(key)).void

end JsBotRepo
