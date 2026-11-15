lexer grammar LexerCommandsOnly;

// a lexer alternative may be just commands (grammars-v4 GoLexer, ANTLR issue 1956)
A : 'a' -> pushMode(M) ;

mode M;
B : 'b' ;
OTHER : -> popMode, channel(HIDDEN) ;
