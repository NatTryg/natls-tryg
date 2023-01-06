package org.amshove.natparse.lexing;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;


import org.amshove.natparse.natural.project.NaturalHeader;
import org.amshove.natparse.natural.project.NaturalProgrammingMode;
import org.amshove.natparse.IPosition;
import org.amshove.natparse.lexing.text.SourceTextScanner;


public class Lexer
{
	private SourceTextScanner scanner;
	private List<SyntaxToken> tokens;
	private List<SyntaxToken> comments;
	private int line;
	private int currentLineStartOffset;
	private Path filePath;
	private NaturalHeader sourceHeader;
	private IPosition relocatedDiagnosticPosition;

	private boolean inParens, inHdr, hdrDone;

	private NaturalProgrammingMode mode = NaturalProgrammingMode.UNKNOWN;
	private int lineIncrement = 10;
	private List<LexerDiagnostic> diagnostics;

	public TokenList lex(String source, Path filePath)
	{
		this.filePath = filePath;
		tokens = new ArrayList<>();
		diagnostics = new ArrayList<>();
		comments = new ArrayList<>();
		scanner = new SourceTextScanner(source);
		sourceHeader = new NaturalHeader(NaturalProgrammingMode.UNKNOWN, 0);
		line = 0;
		currentLineStartOffset = 0;

		while (!scanner.isAtEnd())
		{
			if (!hdrDone && consumeNaturalHeader())
			{
				continue;
			}

			if (consumeComment())
			{
				continue;
			}

			switch (scanner.peek())
			{
				case ' ':
				case '\t':
				case '\r':
					scanner.advance();
					continue;
				case '\n':
					line++;
					scanner.advance();
					currentLineStartOffset = scanner.position();
					continue;
				case '(':
					inParens = true;
					createAndAddCurrentSingleToken(SyntaxKind.LPAREN);
					continue;
				case ')':
					inParens = false;
					createAndAddCurrentSingleToken(SyntaxKind.RPAREN);
					continue;
				case '[':
					createAndAddCurrentSingleToken(SyntaxKind.LBRACKET);
					continue;
				case ']':
					createAndAddCurrentSingleToken(SyntaxKind.RBRACKET);
					continue;
				case '=':
					createAndAddCurrentSingleToken(SyntaxKind.EQUALS_SIGN);
					continue;
				case ':':
					createAndAddFollowupEquals(SyntaxKind.COLON, SyntaxKind.COLON_EQUALS_SIGN);
					continue;
				case '+':
					if (isValidAivStartAfterPlus(scanner.peek(1)))
					{
						consumeIdentifier();
					}
					else
					{
						createAndAddCurrentSingleToken(SyntaxKind.PLUS);
					}
					continue;
				case '-':
					consumeMinusOrStringConcat();
					continue;
				case '*':
					consumeAsteriskOrSystemVariable();
					continue;
				case '/':
					createAndAddCurrentSingleToken(SyntaxKind.SLASH);
					continue;
				case '\\':
					createAndAddCurrentSingleToken(SyntaxKind.BACKSLASH);
					continue;
				case ';':
					createAndAddCurrentSingleToken(SyntaxKind.SEMICOLON);
					continue;
				case '>':
					createAndAddFollowupEquals(SyntaxKind.GREATER_SIGN, SyntaxKind.GREATER_EQUALS_SIGN);
					continue;
				case '<':
					if (tryCreateIfFollowedBy('=', SyntaxKind.LESSER_EQUALS_SIGN)
						|| tryCreateIfFollowedBy('>', SyntaxKind.LESSER_GREATER))
					{
						continue;
					}
					createAndAddCurrentSingleToken(SyntaxKind.LESSER_SIGN);
					continue;
				case '.':
					createAndAddCurrentSingleToken(SyntaxKind.DOT);
					continue;
				case ',':
					createAndAddCurrentSingleToken(SyntaxKind.COMMA);
					continue;
				case '\'':
					consumeString('\'');
					continue;
				case '"':
					consumeString('"');
					continue;
				case '^':
					if (tryCreateIfFollowedBy('=', SyntaxKind.CIRCUMFLEX_EQUAL))
					{
						continue;
					}

					createAndAddCurrentSingleToken(SyntaxKind.CARET);
					continue;
				case '%':
					createAndAddCurrentSingleToken(SyntaxKind.PERCENT);
					continue;
				case '?':
					createAndAddCurrentSingleToken(SyntaxKind.QUESTIONMARK);
					continue;
				case 'h':
				case 'H':
					if (scanner.peek(1) == '\'')
					{
						consumeHexString();
					}
					else
					{
						consumeIdentifierOrKeyword();
					}
					continue;
				case 'a':
				case 'A':
				case 'b':
				case 'B':
				case 'c':
				case 'C':
				case 'd':
				case 'D':
				case 'e':
				case 'E':
				case 'f':
				case 'F':
				case 'g':
				case 'G':
				case 'i':
				case 'I':
				case 'j':
				case 'J':
				case 'k':
				case 'K':
				case 'l':
				case 'L':
				case 'm':
				case 'M':
				case 'n':
				case 'N':
				case 'o':
				case 'O':
				case 'p':
				case 'P':
				case 'q':
				case 'Q':
				case 'r':
				case 'R':
				case 's':
				case 'S':
				case 't':
				case 'T':
				case 'u':
				case 'U':
				case 'v':
				case 'V':
				case 'w':
				case 'W':
				case 'x':
				case 'X':
				case 'y':
				case 'Y':
				case 'z':
				case 'Z':
				case '\u00C5': // (char) 0xc385: // AA
				case '\u00C6': // (char) 0xc386: // AE
				case '\u00D8': // (char) 0xc398: // OE
				case '\u00E6': // (char) 0xc3a6: // ae
				case '\u00E5': // (char) 0xc3a5: // aa
				case '\u00F8': // (char) 0xc3b8: // oe
					consumeIdentifierOrKeyword();
					continue;
				case '#':
				case '&':
					consumeIdentifier();
					continue;
				case '0':
				case '1':
				case '2':
				case '3':
				case '4':
				case '5':
				case '6':
				case '7':
				case '8':
				case '9':
					consumeNumber();
					continue;
				default:
					diagnostics.add(LexerDiagnostic.create(
						"Unknown character [%c]".formatted(scanner.peek()),
						scanner.position(),
						getOffsetInLine(),
						line,
						1,
						filePath,
						LexerError.UNKNOWN_CHARACTER));
					scanner.advance();
			}
		}
		return TokenList.fromTokensAndDiagnostics(filePath, tokens, diagnostics, comments, sourceHeader);
	}

	public void relocateDiagnosticPosition(IPosition diagnosticPosition)
	{
		this.relocatedDiagnosticPosition = diagnosticPosition;
	}

	private void consumeMinusOrStringConcat()
	{
		var lookaheadIndex = findNextNonWhitespaceLookaheadOffset();
		var lookahead = scanner.peek(lookaheadIndex);
		var previousToken = previous();
		var isStringConcatenation = previousToken != null && previousToken.kind() == SyntaxKind.STRING_LITERAL
			&& (lookahead == '\'' || lookahead == '"');
		if (isStringConcatenation)
		{
			var previousString = previousUnsafe();
			var previousStringIndex = tokens.size() - 1;
			scanner.advance(lookaheadIndex);
			consumeString(lookahead);
			var currentString = previousUnsafe();
			var currentStringIndex = tokens.size() - 1;
			if (currentStringIndex >= previousStringIndex)
			{
				tokens.subList(previousStringIndex, currentStringIndex + 1).clear();
			}
			addToken(SyntaxTokenFactory.create(
				SyntaxKind.STRING_LITERAL,
				previousString.offset(),
				previousString.offsetInLine(),
				previousString.line(),
				"'" + previousString.stringValue() + currentString.stringValue() + "'",
				filePath
			));
			return;
		}

		createAndAddCurrentSingleToken(SyntaxKind.MINUS);
	}

	private void consumeAsteriskOrSystemVariable()
	{
		var lookahead = scanner.peek(1);
		switch (lookahead)
		{
			case 'a':
			case 'A':
			case 'c':
			case 'C':
			case 'd':
			case 'D':
			case 'e':
			case 'E':
			case 'i':
			case 'I':
			case 'l':
			case 'L':
			case 'm':
			case 'M':
			case 'n':
			case 'N':
			case 't':
			case 'T':
			case 'o':
			case 'O':
			case 'p':
			case 'P':
			case 's':
			case 'S':
			case 'u':
			case 'U':
			case 'w':
			case 'W':
				break;
			default:
				createAndAddCurrentSingleToken(SyntaxKind.ASTERISK);
				return;

		}

		scanner.start();
		scanner.advance();
		if (scanner.advanceIfIgnoreCase("OCCURRENCE"))
		{
			createAndAdd(SyntaxKind.OCCURRENCE);
			return;
		}
		if (scanner.advanceIfIgnoreCase("OCC"))
		{
			createAndAdd(SyntaxKind.OCC);
			return;
		}
		if (scanner.advanceIfIgnoreCase("DATA"))
		{
			createAndAdd(SyntaxKind.SV_DATA);
			return;
		}
		if (scanner.advanceIfIgnoreCase("LEVEL"))
		{
			createAndAdd(SyntaxKind.SV_LEVEL);
			return;
		}
		if (scanner.advanceIfIgnoreCase("LINEX"))
		{
			createAndAdd(SyntaxKind.LINEX);
			return;
		}
		if (scanner.advanceIfIgnoreCase("LINE-COUNT"))
		{
			createAndAdd(SyntaxKind.LINE_COUNT);
			return;
		}
		if (scanner.advanceIfIgnoreCase("LINESIZE"))
		{
			createAndAdd(SyntaxKind.LINESIZE);
			return;
		}
		if (scanner.advanceIfIgnoreCase("PAGESIZE"))
		{
			createAndAdd(SyntaxKind.PAGESIZE);
			return;
		}
		if (scanner.advanceIfIgnoreCase("TRIM"))
		{
			createAndAdd(SyntaxKind.TRIM);
			return;
		}
		if (scanner.advanceIfIgnoreCase("ERROR-NR"))
		{
			createAndAdd(SyntaxKind.ERROR_NR);
			return;
		}
		if (scanner.advanceIfIgnoreCase("ERROR-LINE"))
		{
			createAndAdd(SyntaxKind.ERROR_LINE);
			return;
		}
		if (scanner.advanceIfIgnoreCase("LINE"))
		{
			createAndAdd(SyntaxKind.LINE);
			return;
		}
		if (scanner.advanceIfIgnoreCase("TIMX"))
		{
			createAndAdd(SyntaxKind.TIMX);
			return;
		}
		if (scanner.advanceIfIgnoreCase("TIMD"))
		{
			createAndAdd(SyntaxKind.TIMD);
			return;
		}
		if (scanner.advanceIfIgnoreCase("TIMN"))
		{
			createAndAdd(SyntaxKind.TIMN);
			return;
		}
		if (scanner.advanceIfIgnoreCase("DAT4E"))
		{
			createAndAdd(SyntaxKind.DAT4E);
			return;
		}
		if (scanner.advanceIfIgnoreCase("DATE"))
		{
			createAndAdd(SyntaxKind.DATE);
			return;
		}
		if (scanner.advanceIfIgnoreCase("DATX"))
		{
			createAndAdd(SyntaxKind.DATX);
			return;
		}
		if (scanner.advanceIfIgnoreCase("DATN"))
		{
			createAndAdd(SyntaxKind.DATN);
			return;
		}
		if (scanner.advanceIfIgnoreCase("DATD"))
		{
			createAndAdd(SyntaxKind.DATD);
			return;
		}
		if (scanner.advanceIfIgnoreCase("DAT4D"))
		{
			createAndAdd(SyntaxKind.DAT4D);
			return;
		}
		if (scanner.advanceIfIgnoreCase("DAT4I"))
		{
			createAndAdd(SyntaxKind.DAT4I);
			return;
		}
		if (scanner.advanceIfIgnoreCase("DATI"))
		{
			createAndAdd(SyntaxKind.DATI);
			return;
		}
		if (scanner.advanceIfIgnoreCase("DATG"))
		{
			createAndAdd(SyntaxKind.DATG);
			return;
		}
		if (scanner.advanceIfIgnoreCase("DAT4J"))
		{
			createAndAdd(SyntaxKind.DAT4J);
			return;
		}
		if (scanner.advanceIfIgnoreCase("DATJ"))
		{
			createAndAdd(SyntaxKind.DATJ);
			return;
		}
		if (scanner.advanceIfIgnoreCase("DAT4U"))
		{
			createAndAdd(SyntaxKind.DAT4U);
			return;
		}
		if (scanner.advanceIfIgnoreCase("DATU"))
		{
			createAndAdd(SyntaxKind.DATU);
			return;
		}
		if (scanner.advanceIfIgnoreCase("DATVS"))
		{
			createAndAdd(SyntaxKind.DATVS);
			return;
		}
		if (scanner.advanceIfIgnoreCase("DATV"))
		{
			createAndAdd(SyntaxKind.DATV);
			return;
		}
		if (scanner.advanceIfIgnoreCase("LANGUAGE"))
		{
			createAndAdd(SyntaxKind.LANGUAGE);
			return;
		}
		if (scanner.advanceIfIgnoreCase("LIBRARY-ID"))
		{
			createAndAdd(SyntaxKind.LIBRARY_ID);
			return;
		}
		if (scanner.advanceIfIgnoreCase("ISN"))
		{
			createAndAdd(SyntaxKind.SV_ISN);
			return;
		}
		if (scanner.advanceIfIgnoreCase("PROGRAM"))
		{
			createAndAdd(SyntaxKind.PROGRAM);
			return;
		}
		if (scanner.advanceIfIgnoreCase("CPU-TIME"))
		{
			createAndAdd(SyntaxKind.CPU_TIME);
			return;
		}
		if (scanner.advanceIfIgnoreCase("ETID"))
		{
			createAndAdd(SyntaxKind.ETID);
			return;
		}
		if (scanner.advanceIfIgnoreCase("INIT-PROGRAM"))
		{
			createAndAdd(SyntaxKind.INIT_PROGRAM);
			return;
		}
		if (scanner.advanceIfIgnoreCase("LBOUND"))
		{
			createAndAdd(SyntaxKind.LBOUND);
			return;
		}
		if (scanner.advanceIfIgnoreCase("UBOUND"))
		{
			createAndAdd(SyntaxKind.UBOUND);
			return;
		}
		if (scanner.advanceIfIgnoreCase("USER"))
		{
			createAndAdd(SyntaxKind.SV_USER);
			return;
		}
		if (scanner.advanceIfIgnoreCase("NUMBER"))
		{
			createAndAdd(SyntaxKind.SV_NUMBER);
			return;
		}
		if (scanner.advanceIfIgnoreCase("CURRENT-UNIT"))
		{
			createAndAdd(SyntaxKind.CURRENT_UNIT);
			return;
		}
		if (scanner.advanceIfIgnoreCase("CURS-COL"))
		{
			createAndAdd(SyntaxKind.CURS_COL);
			return;
		}
		if (scanner.advanceIfIgnoreCase("CURS-LINE"))
		{
			createAndAdd(SyntaxKind.CURS_LINE);
			return;
		}
		if (scanner.advanceIfIgnoreCase("CURS-FIELD"))
		{
			createAndAdd(SyntaxKind.CURS_FIELD);
			return;
		}
		if (scanner.advanceIfIgnoreCase("ERROR-TA"))
		{
			createAndAdd(SyntaxKind.ERROR_TA);
			return;
		}
		if (scanner.advanceIfIgnoreCase("INIT-USER"))
		{
			createAndAdd(SyntaxKind.INIT_USER);
			return;
		}
		if (scanner.advanceIfIgnoreCase("INIT-ID"))
		{
			createAndAdd(SyntaxKind.INIT_ID);
			return;
		}
		if (scanner.advanceIfIgnoreCase("COUNTER"))
		{
			createAndAdd(SyntaxKind.COUNTER);
			return;
		}
		if (scanner.advanceIfIgnoreCase("COM"))
		{
			createAndAdd(SyntaxKind.COM);
			return;
		}
		if (scanner.advanceIfIgnoreCase("PF-KEY"))
		{
			createAndAdd(SyntaxKind.PF_KEY);
			return;
		}
		if (scanner.advanceIfIgnoreCase("MAXVAL"))
		{
			createAndAdd(SyntaxKind.MAXVAL);
			return;
		}
		if (scanner.advanceIfIgnoreCase("MINVAL"))
		{
			createAndAdd(SyntaxKind.MINVAL);
			return;
		}
		if (scanner.advanceIfIgnoreCase("DEVICE"))
		{
			createAndAdd(SyntaxKind.DEVICE);
			return;
		}
		if (scanner.advanceIfIgnoreCase("OPSYS"))
		{
			createAndAdd(SyntaxKind.OPSYS);
			return;
		}
		if (scanner.advanceIfIgnoreCase("TPSYS"))
		{
			createAndAdd(SyntaxKind.TPSYS);
			return;
		}
		if (scanner.advanceIfIgnoreCase("TIMESTMP"))
		{
			createAndAdd(SyntaxKind.TIMESTMP);
			return;
		}
		if (scanner.advanceIfIgnoreCase("APPLIC-ID"))
		{
			createAndAdd(SyntaxKind.APPLIC_ID);
			return;
		}
		if (scanner.advanceIfIgnoreCase("STARTUP"))
		{
			createAndAdd(SyntaxKind.STARTUP);
			return;
		}
		if (scanner.advanceIfIgnoreCase("STEPLIB"))
		{
			createAndAdd(SyntaxKind.STEPLIB);
			return;
		}
		if (scanner.advanceIfIgnoreCase("PAGE-NUMBER"))
		{
			createAndAdd(SyntaxKind.PAGE_NUMBER);
			return;
		}
		if (scanner.advanceIfIgnoreCase("WINDOW-PS"))
		{
			createAndAdd(SyntaxKind.WINDOW_PS);
			return;
		}
		if (scanner.advanceIfIgnoreCase("TRANSLATE"))
		{
			createAndAdd(SyntaxKind.TRANSLATE);
			return;
		}
		if (scanner.advanceIfIgnoreCase("MACHINE-CLASS"))
		{
			createAndAdd(SyntaxKind.MACHINE_CLASS);
			return;
		}
		scanner.rollbackCurrentLexeme();
		createAndAddCurrentSingleToken(SyntaxKind.ASTERISK);
	}

	private void consumeIdentifier()
	{
		scanner.start();
		if(scanner.peek() == '+')
		{
			scanner.advance();
		}

		var isQualified = false;

		while (!scanner.isAtEnd() && !isLineEnd() && isNoWhitespace() && isValidIdentifierCharacter(scanner.peek()))
		{
			if(scanner.peek() == '.')
			{
				isQualified = true;
			}

			if(scanner.peek() == '/' && scanner.peek(1) == '*')
			{
				// Slash is a valid character for identifiers, but an asterisk is not.
				// If a variable is named #MYVAR/* we can safely assume its a variable followed
				// by a comment.
				break;
			}
			scanner.advance();
		}

		var text = scanner.lexemeText();
		if(text.startsWith("+"))
		{
			// Special case. Starting with + could be an AIV, but +123 is meant
			// arithmetically
			var onlyDigits = true;
			for (int i = 1; i < text.length(); i++)
			{
				if(!Character.isDigit(text.charAt(i)))
				{
					onlyDigits = false;
					break;
				}
			}

			if(onlyDigits)
			{
				scanner.rollbackCurrentLexeme();
				createAndAddCurrentSingleToken(SyntaxKind.PLUS);
				return;
			}
		}

		// Handling for C*, T*, P*
		var cStarAtStart = scanner.lexemeLength() == 1 && scanner.peek() == '*';
		var cStarQualified = isQualified && scanner.peek() == '*' && scanner.peek(-2) == '.';
		if (cStarAtStart || cStarQualified)
		{
			scanner.advance();
			while (!scanner.isAtEnd() && !isLineEnd() && isNoWhitespace()
				&& isValidIdentifierCharacter(scanner.peek()))
			{
				scanner.advance();
			}
		}

		if(scanner.peek(-1) == '.')
		{
			createAndAdd(SyntaxKind.LABEL_IDENTIFIER);
		}
		else
		{
			createAndAdd(SyntaxKind.IDENTIFIER);
		}
	}

	private boolean isValidIdentifierCharacter(char character)
	{
		return Character.isAlphabetic(character) || Character.isDigit(character) || character == '-' || character == '/'
			|| character == '@' || character == '$' || character == '&' || character == '#' || character == '.'
			|| character == '_';
	}

	private void consumeIdentifierOrKeyword()
	{
		if(inParens && scanner.peekText("EM="))
		{
			editorMask();
			return;
		}

		if(inParens && scanner.peekText("AD="))
		{
			attributeDefinition();
			return;
		}

		if(inParens && scanner.peekText("CD="))
		{
			colorDefinition();
			return;
		}

		var isQualified = false;
		SyntaxKind kindHint = null;
		scanner.start();

		if (scanner.advanceIf("PF") && Character.isDigit(scanner.peek()))
		{
			while(!scanner.isAtEnd() && Character.isDigit(scanner.peek()))
			{
				scanner.advance();
			}
			createAndAdd(SyntaxKind.PF);
			return;
		}

		var dashCount = 0;
		while (!isLineEnd() && isNoWhitespace() && !scanner.isAtEnd() && isValidIdentifierCharacter(scanner.peek()))
		{

			// Characters from which we can be sure that we're dealing with an identifier
			switch (scanner.peek())
			{
				case '.':
					isQualified = true;
				case '@':
				case '$':
				case '&':
				case '#':
					kindHint = SyntaxKind.IDENTIFIER;
					break;
				case '-':
					dashCount++;
					if (dashCount >1)
					{
						// This might be removed when IDENTIFIER_OR_KEYWORD is gone
						kindHint = SyntaxKind.IDENTIFIER;
					}
					break;
			}

			if (scanner.peek() == '/')
			{ // TODO: this does not work for "KEYWORD/*", eg. END-SUBROUTINE/* bla bla
				kindHint = SyntaxKind.IDENTIFIER;

				if(scanner.peek(1) == '*' && tokens.get(tokens.size() - 1).kind() == SyntaxKind.INCLUDE)
				{
					// The slash belongs to a comment, and we aren't parsing an array definition.
					// TODO(lexermode): This should no longer be needed when the array definition is
					// handled by a parser mode.
					break;
				}
			}

			scanner.advance();
		}

		if ((scanner.peek() == ',' || scanner.peek() == '.') && !isValidIdentifierCharacter(scanner.peek(1))
			&& !isWhitespace(1))
		{
			// TODO(lexermode): This is only needed because the Define Data Parser relies on
			// DataFormats to be identifiers currently.
			// With a fitting lexer mode we can build this better.
			var somethingAsideOfCommaOrDotConsumed = false;
			while (!isLineEnd() && isNoWhitespace() && !scanner.isAtEnd() && Character.isDigit(scanner.peek()))
			{
				somethingAsideOfCommaOrDotConsumed = true;
				scanner.advance();
			}
			if (!isLineEnd() && isNoWhitespace() && !scanner.isAtEnd() && scanner.peek() == '.'
				|| scanner.peek() == ',')
			{
				scanner.advance();
			}
			while (!isLineEnd() && isNoWhitespace() && !scanner.isAtEnd() && Character.isDigit(scanner.peek()))
			{
				somethingAsideOfCommaOrDotConsumed = true;
				scanner.advance();
			}
			if (mightBeDataFormat(scanner.lexemeText()))
			{
				kindHint = SyntaxKind.IDENTIFIER;
			}

			if(!somethingAsideOfCommaOrDotConsumed)
			{
				scanner.advance(-1); // If we didn't find anything that we need, roll back the ./,
			}
		}

		if(scanner.peek(-1) == '.')
		{
			kindHint = SyntaxKind.LABEL_IDENTIFIER;
		}

		// Handling for C*, T*, P*
		var cStarAtStart = scanner.lexemeLength() == 1 && scanner.peek() == '*';
		var cStarQualified = isQualified && scanner.peek() == '*' && scanner.peek(-2) == '.';
		if (cStarAtStart || cStarQualified)
		{
			scanner.advance();
			while (!scanner.isAtEnd() && !isLineEnd() && isNoWhitespace()
				&& isValidIdentifierCharacter(scanner.peek()))
			{
				scanner.advance();
			}
			kindHint = SyntaxKind.IDENTIFIER;
		}

		var lexeme = scanner.lexemeText();

		if (kindHint != null)
		{
			createAndAdd(kindHint);
			return;
		}

		var kind = KeywordTable.getKeyword(lexeme);
		if (kind != null)
		{
			createAndAdd(kind);
		}
		else
		{
			createAndAdd(SyntaxKind.IDENTIFIER);
		}
	}

	private void editorMask()
	{
		scanner.start();
		scanner.advance(3); // EM=
		var isInString = false;
		while(!scanner.isAtEnd() && scanner.peek() != ')')
		{
			if(scanner.peek() == '\'' || scanner.peek() == '"')
			{
				isInString = !isInString;
			}

			if(isWhitespace(0) && !isInString)
			{
				break;
			}

			scanner.advance();
		}

		createAndAdd(SyntaxKind.EM);
	}

	private void attributeDefinition()
	{
		scanner.start();
		scanner.advance(3); // AD=
		while(!scanner.isAtEnd() && isNoWhitespace() && scanner.peek() != ')')
		{
			scanner.advance();
		}

		createAndAdd(SyntaxKind.AD);
	}

	private void colorDefinition()
	{
		scanner.start();
		scanner.advance(3); // CD=
		while(!scanner.isAtEnd() && isNoWhitespace() && scanner.peek() != ')')
		{
			scanner.advance();
		}

		createAndAdd(SyntaxKind.CD);
	}

	private boolean isNoWhitespace()
	{
		return !isWhitespace(0);
	}

	private boolean isWhitespace(int offset)
	{
		return scanner.peek(offset) == ' ' || scanner.peek(offset) == '\t' || scanner.peek(offset) == '\r'
			|| scanner.peek(offset) == '\n';
	}

	private boolean isAtLineStart()
	{
		return scanner.position() - currentLineStartOffset == 0;
	}

	private boolean mightBeDataFormat(String possibleDataFormat)
	{
		var chars = possibleDataFormat.toCharArray();
		if (!Character.isLetter(chars[0]))
		{
			return false;
		}

		var floatingPointCount = 0;
		for (var i = 1; i < chars.length; i++)
		{
			char c = chars[i];
			if (floatingPointCount > 1)
			{
				return false;
			}

			if (c == '.' || c == ',')
			{
				floatingPointCount++;
				continue;
			}

			if (!Character.isDigit(c))
			{
				return false;
			}
		}

		return floatingPointCount < 2;
	}

	private boolean consumeNaturalHeader()
	{
		if (isAtLineStart() && (isSingleAsteriskComment() || isInlineComment()))
		{
			//NOOP
		}
		else
			return false;

		scanner.start();
		while (!isLineEnd() && !scanner.isAtEnd())
		{
			scanner.advance();
		}
		String s = scanner.lexemeText().stripTrailing();

		if (inHdr)
		{
			if (s.contains("* <Natural Source Header"))
			{
				sourceHeader = new NaturalHeader(mode, lineIncrement);
				hdrDone = true;
			}
			else
			{
				if (s.contains("* :Mode"))
				{
					mode = NaturalProgrammingMode.fromString(s.substring(s.length() - 1));
				}
				else
					if (s.contains("* :LineIncrement"))
					{
						s = s.replaceAll("[^0-9]+", "");
						lineIncrement = (Integer.parseInt(s));
					}
			}
		}
		else
		{
			inHdr = s.contains("* >Natural Source Header");
		}
		if (!inHdr)
			scanner.rollbackCurrentLexeme();

		return inHdr;
	}

	private boolean consumeComment()
	{
		var lookahead = scanner.peek(1);
		var isSingleAsteriskComment = isAtLineStart()
			&& scanner.peek() == '*'
			&&
			(lookahead == ' '
				|| lookahead == '*'
				|| lookahead == '\t'
				|| lookahead == '\n'
				|| lookahead == '\r'
				|| lookahead == '/'
				|| lookahead == SourceTextScanner.END_CHARACTER);
		var isInlineComment = isInlineComment();

		if (isInlineComment && tokens.size() > 2)
		{
			// special case like (A5/*) which we might solve naively this way.
			// (A5/*) is a shortcut for (A5/1:*)
			var lastToken = tokens.get(tokens.size() - 1);
			var prevLastToken = tokens.get(tokens.size() - 2);
			if (lastToken.kind() == SyntaxKind.IDENTIFIER && prevLastToken.kind() == SyntaxKind.LPAREN)
			{
				return false;
			}
		}

		if (isSingleAsteriskComment() || isInlineComment)
		{
			scanner.start();
			while (!isLineEnd() && !scanner.isAtEnd())
			{
				scanner.advance();
			}

			var token = SyntaxTokenFactory.create(SyntaxKind.COMMENT,
				scanner.lexemeStart(),
				getOffsetInLine(),
				line,
				scanner.lexemeText(),
				filePath);
			comments.add(token);
			scanner.reset();

			return true;
		}
		return false;
	}

	private boolean isSingleAsteriskComment()
	{
		var lookahead = scanner.peek(1);
		return isAtLineStart()
			&& scanner.peek() == '*'
			&&
			(lookahead == ' '
				|| lookahead == '*'
				|| lookahead == '\t'
				|| lookahead == '\n'
				|| lookahead == '\r'
				|| lookahead == '/'
				|| lookahead == SourceTextScanner.END_CHARACTER);
	}

	private boolean isInlineComment()
	{
		return scanner.peek() == '/' && scanner.peek(1) == '*';
	}

	private void createAndAddCurrentSingleToken(SyntaxKind kind)
	{
		scanner.start();
		scanner.advance();
		createAndAdd(kind);
	}

	private void createAndAddFollowupEquals(SyntaxKind withoutFollowup, SyntaxKind withFollowup)
	{
		scanner.start();
		scanner.advance();
		if (scanner.peek() == '=')
		{
			scanner.advance();
			createAndAdd(withFollowup);
		}
		else
		{
			createAndAdd(withoutFollowup);
		}
	}

	private void consumeNumber()
	{
		scanner.start();
		while (Character.isDigit(scanner.peek()) || scanner.peek() == ',' || scanner.peek() == '.')
		{
			if(scanner.peek() == ',' && !Character.isDigit(scanner.peek(1)))
			{
				break;
			}
			scanner.advance();
		}

		if(scanner.peek() == 'X' || scanner.peek() == 'x')
		{
			scanner.advance();
			createAndAdd(SyntaxKind.OPERAND_SKIP);
			return;
		}

		if(scanner.peek() == 'T')
		{
			scanner.advance();
			createAndAdd(SyntaxKind.TAB_SETTING);
			return;
		}

		if(scanner.peek() == 'E')
		{
			scanner.advance(); // E
			scanner.advance(); // + or -
			while(Character.isDigit(scanner.peek()))
			{
				scanner.advance();
			}
		}

		createAndAdd(SyntaxKind.NUMBER_LITERAL);
	}

	private void consumeHexString()
	{
		scanner.start();
		scanner.advance(2); // H and '
		while (scanner.peek() != '\'' && !scanner.isAtEnd() && !isLineEnd())
		{
			scanner.advance();
		}

		if (scanner.peek() != '\'')
		{
			// Recovery
			while (!isLineEnd() && !scanner.isAtEnd())
			{
				scanner.advance();
			}

			addDiagnostic("Unterminated String literal, expecting closing [']", LexerError.UNTERMINATED_STRING);

			// We can still produce a valid token, although it is unterminated
			createAndAdd(SyntaxKind.STRING_LITERAL);
			return;
		}

		// We don't evaluate the content. Is it worth it? We could convert it to the
		// actual characters.

		scanner.advance();
		createAndAdd(SyntaxKind.STRING_LITERAL);
	}

	private void consumeString(char c)
	{
		scanner.start();
		scanner.advance();
		while (!scanner.isAtEnd() && !isLineEnd())
		{
			if(scanner.peek() == c && scanner.peek(1) == c)
			{
				// escaped ' or "
				scanner.advance();
				scanner.advance();
				continue;
			}

			if(scanner.peek() == c)
			{
				break; // closing character will be consumed later
			}

			scanner.advance();
		}

		if (scanner.peek() != c)
		{
			// Recovery
			while (!isLineEnd() && !scanner.isAtEnd())
			{
				scanner.advance();
			}

			addDiagnostic(
				"Unterminated String literal, expecting closing [%c]".formatted(c),
				LexerError.UNTERMINATED_STRING
			);

			// We can still produce a valid token, although it is unterminated
			createAndAdd(SyntaxKind.STRING_LITERAL);
			return;
		}

		// The current character is the terminating string literal (' or "), therefore
		// it needs to be consumed
		// to be included.
		scanner.advance();
		createAndAdd(SyntaxKind.STRING_LITERAL);
	}

	private void createAndAdd(SyntaxKind kind)
	{
		var token = SyntaxTokenFactory.create(kind,
			scanner.lexemeStart(),
			getOffsetInLine(),
			line,
			scanner.lexemeText(),
			filePath);
		addToken(token);
	}

	private SyntaxToken previous()
	{
		if(tokens.isEmpty())
		{
			return null;
		}
		return tokens.get(tokens.size() - 1);
	}

	/**
	 * Returns the previous consumed token. <strong>Does not do a boundary check</strong>
	 */
	private SyntaxToken previousUnsafe()
	{
		return tokens.get(tokens.size() - 1);
	}

	private int getOffsetInLine()
	{
		if (scanner.lexemeStart() == -1)
		{
			return scanner.position() - currentLineStartOffset;
		}

		return scanner.lexemeStart() - currentLineStartOffset;
	}

	private boolean isLineEnd()
	{
		return scanner.peek() == '\n' || scanner.peek() == '\r' && scanner.peek(1) == '\n';
	}

	private boolean tryCreateIfFollowedBy(char followup, SyntaxKind kind)
	{
		if (scanner.peek(1) == followup)
		{
			scanner.start();
			scanner.advance(2);
			createAndAdd(kind);
			return true;
		}
		return false;
	}

	private void addDiagnostic(String message, LexerError error)
	{
		if(relocatedDiagnosticPosition != null)
		{
			diagnostics.add(LexerDiagnostic.create(
				message,
				scanner.lexemeStart(),
				getOffsetInLine(),
				line,
				scanner.lexemeLength(),
				filePath,
				relocatedDiagnosticPosition,
				error));
		}
		else
		{
			diagnostics.add(LexerDiagnostic.create(
				message,
				scanner.lexemeStart(),
				getOffsetInLine(),
				line,
				scanner.lexemeLength(),
				filePath,
				error));
		}
	}

	private int findNextNonWhitespaceLookaheadOffset()
	{
		var start = 1;
		while (!scanner.isAtEnd() && isWhitespace(start))
		{
			start++;
		}

		return start;
	}

	private void addToken(SyntaxToken token)
	{
		if(token.kind() == SyntaxKind.IDENTIFIER)
		{
			if(token.source().endsWith("."))
			{
				addDiagnostic("Identifiers can not end with '.'", LexerError.INVALID_IDENTIFIER);
			}
		}

		token.setDiagnosticPosition(relocatedDiagnosticPosition);
		tokens.add(token);
		scanner.reset();
	}

	private boolean isValidAivStartAfterPlus(char character)
	{
		// Every identifier name is allowed after the AIV plus, except for numbers
		return isValidIdentifierCharacter(character) && !Character.isDigit(character);
	}
}
