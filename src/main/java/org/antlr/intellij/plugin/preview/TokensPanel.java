package org.antlr.intellij.plugin.preview;

import com.intellij.ui.ColoredTableCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.antlr.intellij.plugin.parsing.LexingResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.List;
import java.util.function.Consumer;

/** The preview's Tokens tab: the token stream of the last parse (or, for a lexer grammar
 *  alone, of tokenizing the input) as a table, one row per token. Tokens off the default
 *  channel are greyed. Input between tokens (skipped, or a lexer error) gets italic rows,
 *  unless "Show skipped text" is off. Selecting a row selects its text in the input editor.
 *  <p>
 *  Prior art: ANTLR Lab's token list and {@code grun -tokens}; see {@link TokenRows}.
 */
class TokensPanel extends JPanel {
	private static final String[] COLUMNS = {"#", "Type", "Text", "Line:Col", "Channel"};

	private final TokensModel model = new TokensModel();
	private final JBTable table = new JBTable(model);
	private final JBCheckBox showSkipped = new JBCheckBox("Show skipped text", true);

	/** All rows, skipped text included; the table shows these or the tokens alone. */
	private List<TokenRows.Row> allRows = List.of();

	/** @param onTokenSelected given the row the user selected */
	TokensPanel(@NotNull Consumer<TokenRows.Row> onTokenSelected) {
		super(new BorderLayout());
		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
		table.setDefaultRenderer(Object.class, new RowRenderer());
		table.getEmptyText().setText("No tokens");
		table.getSelectionModel().addListSelectionListener(e -> {
			if ( e.getValueIsAdjusting() ) return;
			int row = table.getSelectedRow();
			if ( row>=0 ) onTokenSelected.accept(model.rows.get(table.convertRowIndexToModel(row)));
		});
		showSkipped.setToolTipText("Show the input between tokens: text a rule skipped, or no rule matched");
		showSkipped.setBorder(JBUI.Borders.empty(2, 4));
		showSkipped.addActionListener(e -> showRows());
		add(showSkipped, BorderLayout.NORTH);
		add(new JBScrollPane(table), BorderLayout.CENTER);
	}

	/** Shows {@code result}'s tokens; null clears the table. EDT only. */
	void setTokens(@Nullable LexingResult result) {
		allRows = result==null ? List.of()
			: TokenRows.rows(result.tokens(), result.vocabulary(), result.channelNames(), result.errorSpans());
		showRows();
	}

	private void showRows() {
		model.setRows(showSkipped.isSelected() ? allRows
			: allRows.stream().filter(r -> r.kind()==TokenRows.Kind.TOKEN).toList());
	}

	List<TokenRows.Row> getRows() {
		return model.rows;
	}

	JBCheckBox getShowSkipped() {
		return showSkipped;
	}

	JBTable getTable() {
		return table;
	}

	private static class TokensModel extends AbstractTableModel {
		List<TokenRows.Row> rows = List.of();

		void setRows(List<TokenRows.Row> rows) {
			this.rows = rows;
			fireTableDataChanged();
		}

		@Override
		public int getRowCount() {
			return rows.size();
		}

		@Override
		public int getColumnCount() {
			return COLUMNS.length;
		}

		@Override
		public String getColumnName(int column) {
			return COLUMNS[column];
		}

		@Override
		public Object getValueAt(int rowIndex, int columnIndex) {
			TokenRows.Row row = rows.get(rowIndex);
			return switch ( columnIndex ) {
				case 0 -> row.index()>=0 ? String.valueOf(row.index()) : "";
				case 1 -> row.type();
				case 2 -> row.text();
				case 3 -> row.position();
				default -> row.channel();
			};
		}
	}

	private class RowRenderer extends ColoredTableCellRenderer {
		@Override
		protected void customizeCellRenderer(@NotNull JTable table, @Nullable Object value, boolean selected,
											 boolean hasFocus, int row, int column) {
			TokenRows.Row token = model.rows.get(table.convertRowIndexToModel(row));
			append(String.valueOf(value), switch ( token.kind() ) {
				case TOKEN -> token.hidden() ? SimpleTextAttributes.GRAYED_ATTRIBUTES : SimpleTextAttributes.REGULAR_ATTRIBUTES;
				case SKIPPED -> SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES;
				case ERROR -> SimpleTextAttributes.ERROR_ATTRIBUTES;
			});
		}
	}
}
