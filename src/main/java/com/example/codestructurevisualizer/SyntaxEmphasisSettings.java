package com.example.codestructurevisualizer;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;

/**
 * Persists per-token "enabled" flag and optional foreground colour (hex string)
 * for the Syntax Emphasis feature.
 *
 * <p>The colour field is optional: if left blank the token is rendered in its
 * normal syntax colour but with {@link java.awt.Font#BOLD} applied on top.</p>
 */
@State(name = "SyntaxEmphasis", storages = @Storage("codeStructureVisualizer.xml"))
@Service(Service.Level.PROJECT)
public final class SyntaxEmphasisSettings
		implements PersistentStateComponent<SyntaxEmphasisSettings.State> {

	// ── Token catalogue ────────────────────────────────────────────────────────

	public enum TokenKind {
		IF_KEYWORD("if") {
			@Override
			public boolean enabled(State s) {
				return s.ifEnabled;
			}

			@Override
			public void enabled(State s, boolean v) {
				s.ifEnabled = v;
			}

			@Override
			public String color(State s) {
				return s.ifColor;
			}

			@Override
			public void color(State s, String v) {
				s.ifColor = v;
			}
		},
		PARENTHESES("( )") {
			@Override
			public boolean enabled(State s) {
				return s.parenEnabled;
			}

			@Override
			public void enabled(State s, boolean v) {
				s.parenEnabled = v;
			}

			@Override
			public String color(State s) {
				return s.parenColor;
			}

			@Override
			public void color(State s, String v) {
				s.parenColor = v;
			}
		},
		QUEST("?") {
			@Override
			public boolean enabled(State s) {
				return s.questEnabled;
			}

			@Override
			public void enabled(State s, boolean v) {
				s.questEnabled = v;
			}

			@Override
			public String color(State s) {
				return s.questColor;
			}

			@Override
			public void color(State s, String v) {
				s.questColor = v;
			}
		},
		COLON(":") {
			@Override
			public boolean enabled(State s) {
				return s.colonEnabled;
			}

			@Override
			public void enabled(State s, boolean v) {
				s.colonEnabled = v;
			}

			@Override
			public String color(State s) {
				return s.colonColor;
			}

			@Override
			public void color(State s, String v) {
				s.colonColor = v;
			}
		},
		SEMICOLON(";") {
			@Override
			public boolean enabled(State s) {
				return s.semicolonEnabled;
			}

			@Override
			public void enabled(State s, boolean v) {
				s.semicolonEnabled = v;
			}

			@Override
			public String color(State s) {
				return s.semicolonColor;
			}

			@Override
			public void color(State s, String v) {
				s.semicolonColor = v;
			}
		},
		STRING_QUOTES("\"  \"") {
			@Override
			public boolean enabled(State s) {
				return s.stringQuoteEnabled;
			}

			@Override
			public void enabled(State s, boolean v) {
				s.stringQuoteEnabled = v;
			}

			@Override
			public String color(State s) {
				return s.stringQuoteColor;
			}

			@Override
			public void color(State s, String v) {
				s.stringQuoteColor = v;
			}
		},
		DOT(".") {
			@Override
			public boolean enabled(State s) {
				return s.dotEnabled;
			}

			@Override
			public void enabled(State s, boolean v) {
				s.dotEnabled = v;
			}

			@Override
			public String color(State s) {
				return s.dotColor;
			}

			@Override
			public void color(State s, String v) {
				s.dotColor = v;
			}
		},
		BRACES("{ }") {
			@Override
			public boolean enabled(State s) {
				return s.braceEnabled;
			}

			@Override
			public void enabled(State s, boolean v) {
				s.braceEnabled = v;
			}

			@Override
			public String color(State s) {
				return s.braceColor;
			}

			@Override
			public void color(State s, String v) {
				s.braceColor = v;
			}
		},
		BRACKETS("[ ]") {
			@Override
			public boolean enabled(State s) {
				return s.bracketEnabled;
			}

			@Override
			public void enabled(State s, boolean v) {
				s.bracketEnabled = v;
			}

			@Override
			public String color(State s) {
				return s.bracketColor;
			}

			@Override
			public void color(State s, String v) {
				s.bracketColor = v;
			}
		},
		ANGLE_BRACKETS("< >") {
			@Override
			public boolean enabled(State s) {
				return s.angleEnabled;
			}

			@Override
			public void enabled(State s, boolean v) {
				s.angleEnabled = v;
			}

			@Override
			public String color(State s) {
				return s.angleColor;
			}

			@Override
			public void color(State s, String v) {
				s.angleColor = v;
			}
		};

		public final String label;

		TokenKind(String label) {
			this.label = label;
		}

		public abstract boolean enabled(State s);

		public abstract void enabled(State s, boolean v);

		public abstract String color(State s);

		public abstract void color(State s, String v);
	}

	// ── Persistent state (flat public fields – required by XmlSerializer) ──────

	public static final class State {
		// IF keyword
		public boolean ifEnabled = true;
		public String ifColor = "";
		// Parentheses ( )
		public boolean parenEnabled = true;
		public String parenColor = "";
		// Ternary ?
		public boolean questEnabled = true;
		public String questColor = "";
		// Colon :
		public boolean colonEnabled = true;
		public String colonColor = "";
		// Semicolon ;
		public boolean semicolonEnabled = true;
		public String semicolonColor = "";
		// String-literal quote characters
		public boolean stringQuoteEnabled = true;
		public String stringQuoteColor = "";
		// Member-access dot .
		public boolean dotEnabled = true;
		public String dotColor = "";
		// Curly braces { }
		public boolean braceEnabled = true;
		public String braceColor = "";
		// Square brackets [ ]
		public boolean bracketEnabled = true;
		public String bracketColor = "";
		// Angle brackets / generics < >
		public boolean angleEnabled = true;
		public String angleColor = "";
	}

	private State currentState = new State();

	// ── PersistentStateComponent ───────────────────────────────────────────────

	@Override
	public @NotNull State getState() {
		return currentState;
	}

	@Override
	public void loadState(@NotNull State s) {
		this.currentState = s;
	}

	// ── Typed accessors ────────────────────────────────────────────────────────

	public boolean isEnabled(@NotNull TokenKind kind) {
		return kind.enabled(currentState);
	}

	public void setEnabled(@NotNull TokenKind kind, boolean v) {
		kind.enabled(currentState, v);
	}

	public @NotNull String colorHex(@NotNull TokenKind kind) {
		String h = kind.color(currentState);
		return h == null ? "" : h;
	}

	public void setColorHex(@NotNull TokenKind kind, @NotNull String hex) {
		kind.color(currentState, hex.trim());
	}

	/**
	 * Returns the parsed Color, or {@code null} if the hex field is blank / invalid.
	 */
	public @Nullable Color color(@NotNull TokenKind kind) {
		String hex = colorHex(kind);
		if (hex.isBlank()) return null;
		try {
			return Color.decode(hex.startsWith("#") ? hex : "#" + hex);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	public static @NotNull SyntaxEmphasisSettings getInstance(@NotNull Project project) {
		return project.getService(SyntaxEmphasisSettings.class);
	}
}

