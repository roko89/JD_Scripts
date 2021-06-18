package me.leon

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.leon.ip.IpFilterTest
import me.leon.support.*
import org.junit.jupiter.api.Test
import java.text.SimpleDateFormat
import java.util.*

class NodeCrawler {

    private val nodeInfo = "$ROOT/info.md"
    private val customInfo = "防失效github.com/Leon406/Sub "
    private val REG_AD =
        """flyxxl赞助|\([^)]{5,}\)|（.*）|节点更新 ?https?://.+|@SSRSUB-|-付费推荐:.+/ssrsub|https://www.mattkaydiary.com|tg@freebaipiao|@github.com/colatiger-|github.com/freefq - """.toRegex()
    private val REG_AD_REPALCE =
        """海绵云机场 https://fzusrs.xyz|\[free-ss.site]www.kernels.bid|https://gfwservice.xyz|请订阅-KingFu景福@YouTuBe-自动抓取海量免费节点-https://free.kingfu.cf|网址：fly.xxl123.fun \| TG：t.me/flyXXL12345""".toRegex()

    private val maps = linkedMapOf<String, LinkedHashSet<Sub>>()

    /**
     * 1.爬取配置文件对应链接的节点,并去重
     * 2.同时进行可用性测试 tcping
     */
    @Test
    fun crawl() {
        //1.爬取配置文件的订阅
        crawlNodes()
        checkNodes()
        nodeGroup()
//        IpFilterTest().reTestFailIps()
    }

    /**
     * 爬取配置文件数据，并去重写入文件
     */
    private fun crawlNodes() {
        val subs1 = "$ROOT/pool/subpool".readLines()
        val subs2 = "$ROOT/pool/subs".readLines()
        val unavailable = "$ROOT/pool/unavailable".readLines()
//        val subs3 = "$SHARE2/tmp".readLines()
        val sublist = "$ROOT/pool/sublists".readLines()
        val subs3 = sublist.map { it.readFromNet() }.flatMap { it.split("\r\n|\n".toRegex()) }.distinct()
            .also { println("before ${it.size}") }
            .filterNot { unavailable.contains(it) || it.startsWith("#") || it.trim().isEmpty() }
            .also {
                println(it)
                println("after ${it.size}")
            }
        val subs = (subs1 + subs2 + subs3).toHashSet()

        POOL.writeLine()

        runBlocking {
            subs.filterNot { it.trim().startsWith("#") || it.trim().isEmpty() }
                .also { println("共有订阅源：${it.size}") }
                .map { sub ->
                    sub to async(DISPATCHER) {
                        try {
                            Parser.parseFromSub(sub).also { println("$sub ${it.size} ") }
                        } catch (e: Exception) {
                            println("___parse failed $sub  ${e.message}")
                            linkedSetOf()
                        }
                    }
                }
                .map { it.first to it.second.await() }
                .fold(linkedSetOf<Sub>()) { acc, linkedHashSet ->
                    maps[linkedHashSet.first] = linkedHashSet.second
                    acc.apply { acc.addAll(linkedHashSet.second) }
                }.sortedBy {
                    it.apply {
                        name = name.replace(REG_AD, "")
                            .replace(REG_AD_REPALCE, customInfo)
                    }.toUri()
                }
                .also {
                    println("共有节点 ${it.size}")
                    POOL.writeLine(it.joinToString("\n") { it.toUri() })
                }
        }
    }

    /**
     * 节点可用性测试
     */

    fun checkNodes() {
        nodeInfo.writeLine()
        //2.筛选可用节点
        NODE_OK.writeLine()
        val ok: HashSet<Sub>;
        runBlocking {
            ok = Parser.parseFromSub(POOL)
                .map { it to async(DISPATCHER) { it.SERVER.quickConnect(it.serverPort, 2000) } }
                .filter { it.second.await() > -1 }
                .also {
                    println("有效节点数量 ${it.size}".also {
                        nodeInfo.writeLine(
                            "更新时间${timeStamp()}\r\n\r\n" +
                                    "**$it**"
                        )
                    })
                }
                .map { it.first }
                .toHashSet()
                .also { NODE_OK.writeLine(it.joinToString("\n") { it.toUri() }) }
        }

        println("节点分布: ")
        maps.forEach { (t, u) ->
            (ok - (ok - u)).also {
                println("$t ${it.size}/${u.size}")
            }
        }
    }

    fun nodeGroup() {
        NODE_SS.writeLine()
        NODE_SSR.writeLine()
        NODE_V2.writeLine()
        NODE_TR.writeLine()

        Parser.parseFromSub(NODE_OK).groupBy { it.javaClass }.forEach { (t, u) ->
            u.firstOrNull()?.run { name = customInfo + name }
            val data = u.joinToString("\n") { it.toUri() }.b64Encode()
            when (t) {
                SS::class.java -> NODE_SS.writeLine(data)
                    .also { println("ss节点: ${u.size}".also { nodeInfo.writeLine("- $it") }) }
                SSR::class.java -> NODE_SSR.writeLine(data)
                    .also { println("ssr节点: ${u.size}".also { nodeInfo.writeLine("- $it") }) }
                V2ray::class.java -> NODE_V2.writeLine(data)
                    .also { println("v2ray节点: ${u.size}".also { nodeInfo.writeLine("- $it") }) }
                Trojan::class.java -> NODE_TR.writeLine(data)
                    .also { println("trojan节点: ${u.size}".also { nodeInfo.writeLine("- $it") }) }
            }
        }
    }

    @Test
    fun removeAd() {
        Parser.parseFromSub(NODE_OK)
            .map { it.also { it.name = it.name.replace(REG_AD, "").replace(REG_AD_REPALCE, customInfo) } }
            .forEach {
                println(it.name)
            }
    }

    @Test
    fun nodeNationGroup() {
        Parser.parseFromSub(NODE_OK).groupBy { it.SERVER.ipCountryZh() }
            .forEach { (t, u) ->
                println("$t: ${u.size}")
                if (t == "UNKNOWN") println(u.map { it.SERVER })
            }
    }

    /**
     * 上面筛好节点后,进行第三方或者本地节点测速
     * 进行节点分组
     * 测速地址
     *  - http://gz.cloudtest.cc/
     *  - http://a.cloudtest.icu/
     */
    @Test
    fun availableSpeedTest() {
        Parser.parseFromSub(NODE_OK).filterIsInstance<V2ray>()
            .chunked(130)
            .mapIndexed { index, list ->
                list.map(Sub::toUri)
                    .subList(0.takeIf { index == 0 } ?: 80, list.size)
                    .also { println(it.joinToString("|")) }
            }
    }

    /**
     *，将网站测速后的结果复制到 speedtest.txt
     *  F12 控制台输入以下内容,提取有效节点信息,默认提取速度大于1MB/s的节点
     * <code>
     *     var rrs=document.querySelectorAll("tr.el-table__row");var ll=[];for(var i=0;i<rrs.length;i++){console.log("____");if(rrs[i].children[4].innerText.indexOf("MB")>0&& Number(rrs[i].children[4].innerText.replace("MB","")) >1){ll.push(rrs[i].children[1].innerText+"|" +rrs[i].children[4].innerText);}};ll.join("\n");
     * </code>
     * 最后进行分享链接生成
     */
    @Test
    fun speedTestResultParse() {
        val map =
            Parser.parseFromSub(NODE_OK)
                .also { println(it.size) }
                .fold(mutableMapOf<String, Sub>()) { acc, sub ->
                    acc.apply { acc[sub.name] = sub }
                }
        NODE_SS2.writeLine()
        NODE_SSR2.writeLine()
        NODE_V22.writeLine()
        NODE_TR2.writeLine()
        SPEED_TEST_RESULT.readLines()
            .distinct()
            .map { it.substringBeforeLast('|') to it.substringAfterLast('|') }
            .sortedByDescending { it.second.replace("Mb|MB".toRegex(), "").toFloat() }
            .filter { map[it.first] != null }
            .groupBy { map[it.first]!!.javaClass }
            .forEach { (t, u) ->
                val data = u.joinToString("\n") {
                    map[it.first]!!.apply {
                        with(SERVER.ipCityZh()) {
                            name = (this?.takeUnless { name.contains(it) }?.run { this + "_" }
                                ?: "") + (name.substringBeforeLast('|') + "|" + it.second)
                        }

                    }.also { println(it.name) }.toUri()
                }
                    .b64Encode()
                when (t) {
                    SS::class.java -> NODE_SS2.writeLine(data)
                        .also { println("ss节点: ${u.size}") }
                    SSR::class.java -> NODE_SSR2.writeLine(data)
                        .also { println("ssr节点: ${u.size}") }
                    V2ray::class.java -> NODE_V22.writeLine(data)
                        .also { println("v2ray节点: ${u.size}") }
                    Trojan::class.java -> NODE_TR2.writeLine(data)
                        .also { println("trojan节点: ${u.size}") }
                }
            }
    }
}