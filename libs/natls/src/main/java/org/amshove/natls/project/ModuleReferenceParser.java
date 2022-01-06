package org.amshove.natls.project;

import org.amshove.natparse.lexing.Lexer;
import org.amshove.natparse.lexing.SyntaxKind;
import org.amshove.natparse.lexing.TokenList;

import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

/**
 * A light parser that just looks for outgoing references of a
 * {@link LanguageServerFile} based on tokens.
 * It does not analyze anything else and is intended to fastly build
 * the dependency tree of a project.
 */
public class ModuleReferenceParser
{
	public void parseReferences(LanguageServerFile file)
	{
		var lexer = new Lexer();
		var path = file.getPath();
		try
		{
			System.err.printf("Start %s%n", file.getReferableName());
			var start = System.currentTimeMillis();
			var tokens = lexer.lex(Files.readString(path), path);
			var calledModules = processReferences(tokens);
			for (var calledModule : calledModules)
			{
				if (calledModule != null)
				{
					var calledFile = file.getLibrary().provideNaturalFile(calledModule, true);
					if (calledFile != null)
					{
						calledFile.addIncomingReference(file);
						file.addOutgoingReference(calledFile);
						System.err.printf("\tAdding reference: %s -> %s%n", file.getReferableName(), calledFile.getReferableName());
					}
					else
					{
						System.err.printf("\t%s not found%n", calledModule);
					}
				}
			}
			var end = System.currentTimeMillis();
			System.err.printf("%s %dms%n", file.getReferableName(), (end - start));
		}
		catch (IOException e)
		{
			// do not interrupt project indexing
		}
	}

	private Set<String> processReferences(TokenList tokens)
	{
		var calledModules = new HashSet<String>();
		var definedSubroutines = new HashSet<String>();
		var calledSubroutines = new HashSet<String>();
		while (!tokens.isAtEnd())
		{
			switch (tokens.peek().kind())
			{
				case USING -> calledModules.add(processUsing(tokens));
				case DEFINE -> {
					if (tokens.peek(1).kind() != SyntaxKind.DATA)
					{
						definedSubroutines.add(processSubroutine(tokens));
					}
				}
				case PERFORM -> calledSubroutines.add(processPerform(tokens));
				case CALLNAT -> calledModules.add(processCallnat(tokens));
				case FETCH -> calledModules.add(processFetch(tokens));
			}

			tokens.advance();
		}

		for (var calledSubroutine : calledSubroutines)
		{
			if (!definedSubroutines.contains(calledSubroutine))
			{
				calledModules.add(calledSubroutine);
			}
		}

		return calledModules;
	}

	private String processFetch(TokenList tokens)
	{
		tokens.advance(); // fetch
		tokens.advance(); // repeat/return
		if (tokens.peek().kind() == SyntaxKind.STRING)
		{
			return tokens.peek().stringValue().toUpperCase();
		}
		return null; // variable
	}

	private String processCallnat(TokenList tokens)
	{
		tokens.advance(); // callnat
		if (tokens.peek().kind() == SyntaxKind.STRING)
		{
			return tokens.peek().stringValue().toUpperCase();
		}
		return null; // variable
	}

	private String processSubroutine(TokenList tokens)
	{
		tokens.advance(); // define
		if (tokens.peek().kind() == SyntaxKind.SUBROUTINE)
		{
			tokens.advance(); // subroutine
		}
		return tokens.peek().symbolName();
	}

	private String processUsing(TokenList tokens)
	{
		tokens.advance(); // using
		return tokens.peek().symbolName();
	}

	private String processPerform(TokenList tokens)
	{
		tokens.advance(); // perform
		return tokens.peek().symbolName();
	}
}
