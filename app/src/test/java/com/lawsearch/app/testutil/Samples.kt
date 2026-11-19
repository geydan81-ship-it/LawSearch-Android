package com.lawsearch.app.testutil

import com.lawsearch.app.data.LawJson
import com.lawsearch.app.data.LawJsonCodec
import java.io.File

/**
 * 测试用样本数据定位。
 *
 * 默认取工作区 ../laws（规格书附录里的样本目录）；
 * 也可用系统属性 -Dsamples.dir=<path> 覆盖；找不到时返回 null。
 */
object Samples {
    private val dir: File? by lazy {
        val candidates = ArrayList<File>()
        System.getProperty("samples.dir")?.let { candidates.add(File(it)) }
        candidates.add(File("..", "laws"))
        candidates.add(File("../../laws"))
        candidates.add(File("../..", "laws"))
        candidates.add(File(System.getProperty("user.dir"), "../laws"))
        candidates.firstOrNull { it.isDirectory && File(it, "1349e6aadd4947fe83ff811739825d3c.json").exists() }
    }

    val available: Boolean get() = dir != null

    val path: String get() = dir?.absolutePath ?: "(not found)"

    fun read(name: String): String? {
        val d = dir ?: return null
        val f = File(d, name)
        return if (f.exists()) f.readText() else null
    }

    fun law(name: String): LawJson? = read(name)?.let { LawJsonCodec.decode(it) }

    const val MARITIME = "1349e6aadd4947fe83ff811739825d3c.json"
    const val ECO_CODE = "96630961659b4d87a65b7b1c595097fa.json"
    const val CONSTITUTION_1982 = "2c909fdd678bf17901678bf59eb80037.json"
    const val CONSTITUTION_2018 = "2c909fdd678bf17901678bf5a483004b.json"
    const val AMENDMENT_2004 = "2c909fdd678bf17901678bf59d420023.json"
}
