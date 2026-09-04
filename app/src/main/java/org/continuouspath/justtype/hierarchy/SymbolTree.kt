package org.continuouspath.justtype.hierarchy

import android.content.res.AssetManager
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/** Three-tier symbol-hierarchy taxonomy from CLAUDE_CODE_BRIEF.md. */
enum class SymbolTier { PRIMARY, PRIMARY_ALT, UNCOMMON }

/** User-facing rarity setting; equals or exceeds the tier of every visible leaf. */
enum class SymbolRaritySetting { PRIMARY_ONLY, PRIMARY_ALT, UNCOMMON }

private fun SymbolTier.includedAt(setting: SymbolRaritySetting): Boolean = when (setting) {
	SymbolRaritySetting.PRIMARY_ONLY -> this == SymbolTier.PRIMARY
	SymbolRaritySetting.PRIMARY_ALT -> this == SymbolTier.PRIMARY || this == SymbolTier.PRIMARY_ALT
	SymbolRaritySetting.UNCOMMON -> true
}

private fun parseTier(s: String?): SymbolTier = when (s) {
	"primary" -> SymbolTier.PRIMARY
	"primary_alt" -> SymbolTier.PRIMARY_ALT
	"uncommon" -> SymbolTier.UNCOMMON
	else -> throw IllegalArgumentException("Unknown symbol tier: $s")
}

sealed class SymbolNode {
	abstract val label: String
}

data class SymbolLeaf(val char: String, val tier: SymbolTier) : SymbolNode() {
	override val label: String = char
}

data class SymbolBranch(override val label: String, val children: List<SymbolNode>) : SymbolNode()

/** Result of applying the render rule to a slot. */
sealed class RenderedSlot {
	object Empty : RenderedSlot()
	data class Leaf(val char: String) : RenderedSlot()
	data class Branch(val preview: List<String>, val target: SymbolNode) : RenderedSlot()
}

/** Loads `assets/hierarchies/symbol_hierarchy.json` into the in-memory tree. Root is a synthetic branch. */
fun loadSymbolTree(assets: AssetManager): SymbolBranch {
	val text = assets.open("hierarchies/symbol_hierarchy.json").use { input ->
		BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
	}
	val root = JSONObject(text).getJSONArray("symbol_hierarchy")
	val children = (0 until root.length()).map { parseNode(root.getJSONObject(it)) }
	return SymbolBranch("ROOT", children)
}

private fun parseNode(obj: JSONObject): SymbolNode = when (obj.getString("k")) {
	"leaf" -> SymbolLeaf(char = obj.getString("char"), tier = parseTier(obj.getString("tier")))
	"branch" -> {
		val arr = obj.getJSONArray("children")
		SymbolBranch(
			label = obj.getString("label"),
			children = (0 until arr.length()).map { parseNode(arr.getJSONObject(it)) },
		)
	}
	else -> throw IllegalArgumentException("Unknown node kind: ${obj.getString("k")}")
}

/** Recursively count all leaves visible at [setting]. */
fun visibleLeaves(node: SymbolNode, setting: SymbolRaritySetting): List<SymbolLeaf> = when (node) {
	is SymbolLeaf -> if (node.tier.includedAt(setting)) listOf(node) else emptyList()
	is SymbolBranch -> node.children.flatMap { visibleLeaves(it, setting) }
}

/** Drill past branches that have only one visible child path. */
private fun collapseSingletons(node: SymbolNode, setting: SymbolRaritySetting): SymbolNode = when (node) {
	is SymbolLeaf -> node
	is SymbolBranch -> {
		val visibleChildren = node.children.filter { visibleLeaves(it, setting).isNotEmpty() }
		if (visibleChildren.size == 1) collapseSingletons(visibleChildren.single(), setting) else node
	}
}

private fun previewGlyphs(node: SymbolNode, setting: SymbolRaritySetting, max: Int = 9): List<String> {
	val leaves = visibleLeaves(node, setting)
	return leaves.take(max).map { it.char }
}

/** Apply the brief's render rule to a single slot. */
fun renderSlot(node: SymbolNode, setting: SymbolRaritySetting): RenderedSlot {
	val leaves = visibleLeaves(node, setting)
	return when (leaves.size) {
		0 -> RenderedSlot.Empty
		1 -> RenderedSlot.Leaf(leaves.single().char)
		else -> {
			val descended = collapseSingletons(node, setting)
			RenderedSlot.Branch(preview = previewGlyphs(descended, setting), target = descended)
		}
	}
}

/** Render the children of [branch] as 6 slots (or however many it has). */
fun renderLevel(branch: SymbolBranch, setting: SymbolRaritySetting): List<RenderedSlot> = branch.children.map { renderSlot(it, setting) }
