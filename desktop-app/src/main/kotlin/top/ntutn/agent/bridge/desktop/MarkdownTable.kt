package top.ntutn.agent.bridge.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.elements.MarkdownDivider
import com.mikepenz.markdown.compose.elements.MarkdownTableBasicText
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes.HEADER
import org.intellij.markdown.flavours.gfm.GFMElementTypes.ROW
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.TABLE_SEPARATOR

/**
 * Keeps a wide table inside the message width while exposing the complete table through a
 * visible, draggable horizontal scrollbar. Cells wrap instead of using the renderer's default
 * single-line ellipsis, so scrolling never leaves hidden cell text inaccessible.
 */
@Composable
internal fun ScrollableMarkdownTable(model: MarkdownComponentModel) {
    val tableMaxWidth = LocalMarkdownDimens.current.tableMaxWidth
    val tableCellWidth = LocalMarkdownDimens.current.tableCellWidth
    val tableCornerSize = LocalMarkdownDimens.current.tableCornerSize
    val tableBackground = LocalMarkdownColors.current.tableBackground
    val columnsCount = remember(model.node) {
        model.node.findChildOfType(HEADER)?.children?.count { it.type == CELL } ?: 0
    }
    val rowsCount = remember(model.node) {
        model.node.children.count { it.type == ROW } + 1
    }
    val columnWidths = rememberMarkdownTableColumnWidths(model, columnsCount, tableCellWidth)
    val tableWidth = columnWidths.fold(0.dp) { total, width -> total + width }
    val horizontalScrollState = rememberScrollState()

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val viewportWidth = if (tableMaxWidth == Dp.Unspecified) maxWidth else minOf(maxWidth, tableMaxWidth)
        val scrollable = shouldScrollMarkdownTable(tableWidth, viewportWidth)
        val containerWidth = minOf(tableWidth, viewportWidth)
        Column(
            modifier = Modifier
                .width(containerWidth)
                .background(tableBackground, RoundedCornerShape(tableCornerSize))
                .semantics {
                    collectionInfo = CollectionInfo(rowCount = rowsCount, columnCount = columnsCount)
                },
        ) {
            Column(
                modifier = Modifier
                    .horizontalScroll(horizontalScrollState)
                    .requiredWidth(tableWidth),
            ) {
                MarkdownTableRows(model, columnWidths)
            }
            if (scrollable) {
                HorizontalScrollbar(
                    adapter = rememberScrollbarAdapter(horizontalScrollState),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun rememberMarkdownTableColumnWidths(
    model: MarkdownComponentModel,
    columnsCount: Int,
    minimumWidth: Dp,
): List<Dp> {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val cellPadding = LocalMarkdownDimens.current.tableCellPadding
    val tableRows = remember(model.node) {
        model.node.children.filter { it.type == HEADER || it.type == ROW }
    }

    return remember(model.content, model.typography.table, tableRows, textMeasurer, density, cellPadding, minimumWidth) {
        List(columnsCount) { columnIndex ->
            val measuredWidth = tableRows.maxOfOrNull { row ->
                val cell = row.children.filter { it.type == CELL }.getOrNull(columnIndex)
                    ?: return@maxOfOrNull 0.dp
                val style = if (row.type == HEADER) {
                    model.typography.table.copy(fontWeight = FontWeight.Bold)
                } else {
                    model.typography.table
                }
                val textWidthPx = textMeasurer.measure(
                    text = cell.getUnescapedTextInNode(model.content).trim('|', ' '),
                    style = style,
                    softWrap = false,
                    maxLines = 1,
                ).size.width
                with(density) { textWidthPx.toDp() }
            } ?: 0.dp
            constrainMarkdownTableColumnWidth(
                measuredTextWidth = measuredWidth,
                horizontalPadding = cellPadding,
                minimumWidth = minimumWidth,
                maximumWidth = 480.dp,
            )
        }
    }
}

@Composable
private fun MarkdownTableRows(model: MarkdownComponentModel, columnWidths: List<Dp>) {
    var rowIndex = 1
    model.node.children.forEach { child ->
        when (child.type) {
            HEADER -> MarkdownAdaptiveTableRow(
                content = model.content,
                row = child,
                columnWidths = columnWidths,
                style = model.typography.table,
                rowIndex = 0,
                isHeader = true,
            )
            ROW -> {
                MarkdownAdaptiveTableRow(
                    content = model.content,
                    row = child,
                    columnWidths = columnWidths,
                    style = model.typography.table,
                    rowIndex = rowIndex,
                    isHeader = false,
                )
                rowIndex++
            }
            TABLE_SEPARATOR -> MarkdownDivider()
        }
    }
}

@Composable
private fun MarkdownAdaptiveTableRow(
    content: String,
    row: ASTNode,
    columnWidths: List<Dp>,
    style: TextStyle,
    rowIndex: Int,
    isHeader: Boolean,
) {
    val cellPadding = LocalMarkdownDimens.current.tableCellPadding
    val tableWidth = columnWidths.fold(0.dp) { total, width -> total + width }
    Row(
        modifier = Modifier.requiredWidth(tableWidth).height(IntrinsicSize.Max),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        row.children.filter { it.type == CELL }.forEachIndexed { columnIndex, cell ->
            Column(
                modifier = Modifier
                    .width(columnWidths.getOrElse(columnIndex) { 160.dp })
                    .padding(cellPadding)
                    .semantics {
                        if (isHeader) heading()
                        collectionItemInfo = CollectionItemInfo(
                            rowIndex = rowIndex,
                            rowSpan = 1,
                            columnIndex = columnIndex,
                            columnSpan = 1,
                        )
                    },
            ) {
                MarkdownTableBasicText(
                    content = content,
                    cell = cell,
                    style = if (isHeader) style.copy(fontWeight = FontWeight.Bold) else style,
                    maxLines = Int.MAX_VALUE,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

internal fun shouldScrollMarkdownTable(
    tableWidth: Dp,
    viewportWidth: Dp,
): Boolean = tableWidth > viewportWidth

internal fun constrainMarkdownTableColumnWidth(
    measuredTextWidth: Dp,
    horizontalPadding: Dp,
    minimumWidth: Dp,
    maximumWidth: Dp,
): Dp = (measuredTextWidth + horizontalPadding * 2).coerceIn(minimumWidth, maximumWidth)
