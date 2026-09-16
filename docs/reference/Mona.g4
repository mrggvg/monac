grammar Mona;

// Reference specification. The parser is hand-written (recursive descent for
// declarations and statements, Pratt for expressions), so this file documents the
// grammar rather than generating it. Keep the two in step.

program
    : top_level+ EOF
    ;

top_level
    : function_definition
    | function_declaration
    | global_declaration
    | struct_declaration
    | enum_declaration
    ;

// Declared at file scope only, so a member's type is resolvable without scoping
// rules for types. Two structs may name each other, and a struct may name itself
// through a pointer — the layout pass runs to a fixpoint for exactly that.
// A union is laid out by the same pass, with every member at offset 0, and shares
// its namespace with struct, as in C.
struct_declaration
    : (STRUCT | UNION) IDENTIFIER LBRACE struct_member+ RBRACE SEMI
    ;

// A member may point to const, but may not itself be const.
struct_member
    : type declarator SEMI
    ;

// Each enumerator is a word constant, one more than the one before unless it says
// otherwise, and a value may use the names before it. A trailing comma is allowed.
// Declared at file scope, or inside a block, where its names are scoped to it.
enum_declaration
    : ENUM IDENTIFIER? LBRACE enumerator (COMMA enumerator)* COMMA? RBRACE SEMI
    ;

enumerator
    : IDENTIFIER (ASSIGN assignment_expression)?
    ;

function_definition
    : specifiers type IDENTIFIER LPAREN parameter_list? RPAREN block
    ;

// A prototype. Parameter names are optional here and required in a definition, and
// every declaration of a function must agree with every other. Calling one that is
// never defined is an error at the call.
function_declaration
    : specifiers type IDENTIFIER LPAREN parameter_list? RPAREN SEMI
    ;

// A global is emitted as a DW or DB directive after the code, so its initializer
// must fold to a constant: there is no startup code that could evaluate one.
global_declaration
    : specifiers type declarator (ASSIGN initializer)? SEMI
    ;

// 'static' and 'const', in either order. At file scope 'static' changes nothing:
// with one file and no linker there is no linkage to narrow.
specifiers
    : (STATIC | CONST)*
    ;

// A base type with any 'const' around it, then any number of '*', each of which
// may be const in its own right: in 'const byte* const p' both are read-only.
type
    : CONST* base_type CONST* (STAR CONST*)*
    ;

// 'struct' is written at every use, as in C89. Without it the parser would need to
// know whether an identifier names a type before it could tell a declaration from
// an expression statement, which is the lexer feedback loop C is famous for. The
// keyword keeps this grammar context-free, and 'enum' works the same way.
base_type
    : VOID
    | BYTE              // unsigned 8-bit
    | WORD              // unsigned 16-bit
    | SBYTE             // signed 8-bit
    | SWORD             // signed 16-bit
    | STRUCT IDENTIFIER
    | UNION IDENTIFIER
    | ENUM IDENTIFIER   // a word, with a name
    ;

// Any constant expression: a number, an enum constant, a const with a constant
// initializer, sizeof, or arithmetic on them. Checked in analysis, not here. Only
// the first may be empty, and then an initializer counts it. '[2][3]' is two rows
// of three.
array_suffix
    : LBRACKET assignment_expression? RBRACKET
    ;

// A value, or a braced list: in order, nested for nested aggregates or flat as C
// allows, and anything left out is zero. A string initializes an array of bytes. A
// global's values must be numbers, or the addresses of strings, globals and
// functions. No designators.
initializer
    : assignment_expression
    | LBRACE (initializer (COMMA initializer)* COMMA?)? RBRACE
    ;

parameter_list
    : VOID                              // no parameters, as in C
    | parameter (COMMA parameter)*
    ;

// An array parameter is a pointer to its first element, as in C: 'word v[]' and
// 'word v[8]' are both a word*, and 'word m[][3]' points to rows of three.
parameter
    : type (IDENTIFIER? array_suffix* | function_pointer)
    ;

// A name and its array dimensions, or C's function-pointer declarator around one.
declarator
    : IDENTIFIER array_suffix*
    | function_pointer
    ;

// 'word (*op)(word, word)' is a pointer to a function; 'word (*ops[4])(word)' is an
// array of them; in a cast or a sizeof the name is left out: '(word (*)(word))x'.
// The type still begins with a keyword, so the grammar stays context-free.
function_pointer
    : LPAREN STAR IDENTIFIER? array_suffix* RPAREN LPAREN parameter_list? RPAREN
    ;

block
    : LBRACE block_item* RBRACE
    ;

block_item
    : declaration
    | enum_declaration
    | statement
    ;

// A static local is a global only its block can name, so its initializer must be a
// constant. A const local needs an initializer.
declaration
    : specifiers type declarator (ASSIGN initializer)? SEMI
    ;

statement
    : RETURN expression? SEMI
    | block
    | IF LPAREN expression RPAREN statement (ELSE statement)?
    | WHILE LPAREN expression RPAREN statement
    | DO statement WHILE LPAREN expression RPAREN SEMI
    | FOR LPAREN (declaration | expression SEMI | SEMI) expression? SEMI expression? RPAREN statement
    | SWITCH LPAREN expression RPAREN LBRACE switch_case* RBRACE
    | BREAK SEMI
    | CONTINUE SEMI
    | GOTO IDENTIFIER SEMI              // within the function; never into a block
    | IDENTIFIER COLON statement        // a label
    | expression SEMI
    | SEMI
    ;

// Case labels must fold to a constant, and may name one. An arm holds block items
// rather than a block, which is what gives C's fall-through.
switch_case
    : (CASE assignment_expression | DEFAULT) COLON block_item*
    ;

// Expressions, lowest binding power first. Follows C, including C's placement of
// the bitwise operators below the comparisons.
expression                                              // the comma operator
    : assignment_expression (COMMA assignment_expression)*
    ;

assignment_expression                                   // right associative
    : conditional_expression
    | unary_expression assignment_operator assignment_expression
    ;

assignment_operator
    : ASSIGN | PLUS_ASSIGN | MINUS_ASSIGN | STAR_ASSIGN | SLASH_ASSIGN
    | PERCENT_ASSIGN | AMP_ASSIGN | PIPE_ASSIGN | CARET_ASSIGN
    | SHL_ASSIGN | SHR_ASSIGN
    ;

conditional_expression                                  // right associative
    : logical_or_expression (QUESTION expression COLON conditional_expression)?
    ;

logical_or_expression   : logical_and_expression (OR_OR logical_and_expression)* ;
logical_and_expression  : bit_or_expression (AND_AND bit_or_expression)* ;
bit_or_expression       : bit_xor_expression (PIPE bit_xor_expression)* ;
bit_xor_expression      : bit_and_expression (CARET bit_and_expression)* ;
bit_and_expression      : equality_expression (AMPERSAND equality_expression)* ;
equality_expression     : relational_expression ((EQUAL | NOT_EQUAL) relational_expression)* ;
relational_expression   : shift_expression ((LESS | LESS_EQUAL | GREATER | GREATER_EQUAL) shift_expression)* ;
shift_expression        : additive_expression ((SHIFT_LEFT | SHIFT_RIGHT) additive_expression)* ;
additive_expression     : multiplicative_expression ((PLUS | DASH) multiplicative_expression)* ;
multiplicative_expression : unary_expression ((STAR | SLASH | PERCENT) unary_expression)* ;

unary_expression
    : (DASH | PLUS | BANG | TILDE | AMPERSAND | STAR) unary_expression
    | (PLUS_PLUS | MINUS_MINUS) unary_expression    // steps, then yields the new value
    | LPAREN type function_pointer? RPAREN unary_expression   // a cast
    | sizeof_expression
    | postfix_expression
    ;

// The parenthesis is required for both forms, unlike C. Which form it is comes from
// the next token: every type begins with a keyword and no expression does — which
// is also how a cast is told from a parenthesised expression.
sizeof_expression
    : SIZEOF LPAREN (type function_pointer? | expression) RPAREN
    ;

postfix_expression
    : primary_expression
      ( LBRACKET expression RBRACKET      // a[i] means *(a + i)
      | DOT IDENTIFIER                    // s.field
      | ARROW IDENTIFIER                  // p->field
      | PLUS_PLUS | MINUS_MINUS           // yields the old value, then steps
      | LPAREN argument_list? RPAREN      // a call through what came before: (*fp)(x)
      )*
    ;

primary_expression
    : IDENTIFIER
    | IDENTIFIER LPAREN argument_list? RPAREN     // call
    | CONSTANT
    | CHAR_LITERAL
    | STRING_LITERAL          // a byte* into read-only data
    | LPAREN expression RPAREN
    ;

// A comma here separates arguments; the comma operator needs parentheses.
argument_list
    : assignment_expression (COMMA assignment_expression)*
    ;

// Keywords
VOID    : 'void' ;
STRUCT  : 'struct' ;
UNION   : 'union' ;
ENUM    : 'enum' ;
SIZEOF  : 'sizeof' ;
CONST   : 'const' ;
STATIC  : 'static' ;
BYTE    : 'byte' ;
WORD    : 'word' ;
SBYTE   : 'sbyte' ;
SWORD   : 'sword' ;

RETURN  : 'return' ;
IF      : 'if' ;
ELSE    : 'else' ;
WHILE   : 'while' ;
DO      : 'do' ;
FOR     : 'for' ;
BREAK   : 'break' ;
CONTINUE: 'continue' ;
GOTO    : 'goto' ;
SWITCH  : 'switch' ;
CASE    : 'case' ;
DEFAULT : 'default' ;

// Delimiters
LPAREN  : '(' ;
RPAREN  : ')' ;
LBRACE  : '{' ;
RBRACE  : '}' ;
LBRACKET: '[' ;
RBRACKET: ']' ;

COMMA   : ',' ;
SEMI    : ';' ;
COLON   : ':' ;
QUESTION: '?' ;
DOT     : '.' ;
ARROW   : '->' ;

// Operators
STAR    : '*' ;
SLASH   : '/' ;
PERCENT : '%' ;
PLUS    : '+' ;
DASH    : '-' ;

AMPERSAND   : '&' ;
PIPE        : '|' ;
CARET       : '^' ;
TILDE       : '~' ;
BANG        : '!' ;
PLUS_PLUS   : '++' ;
MINUS_MINUS : '--' ;
SHIFT_LEFT  : '<<' ;
SHIFT_RIGHT : '>>' ;

EQUAL         : '==' ;
NOT_EQUAL     : '!=' ;
LESS          : '<' ;
LESS_EQUAL    : '<=' ;
GREATER       : '>' ;
GREATER_EQUAL : '>=' ;

AND_AND : '&&' ;
OR_OR   : '||' ;

ASSIGN         : '=' ;
PLUS_ASSIGN    : '+=' ;
MINUS_ASSIGN   : '-=' ;
STAR_ASSIGN    : '*=' ;
SLASH_ASSIGN   : '/=' ;
PERCENT_ASSIGN : '%=' ;
AMP_ASSIGN     : '&=' ;
PIPE_ASSIGN    : '|=' ;
CARET_ASSIGN   : '^=' ;
SHL_ASSIGN     : '<<=' ;
SHR_ASSIGN     : '>>=' ;

IDENTIFIER  : [a-zA-Z_][a-zA-Z0-9_]* ;
// As in C: decimal, a leading 0 for octal, 0x and 0b in either case, a ' between
// two digits as a C23 separator, and the suffixes u, l and ll, which change nothing
// here because every integer is 16 bits.
CONSTANT    : ( [1-9] ( '\''? [0-9] )*
              | '0' ( '\''? [0-7] )*
              | '0' [xX] [0-9a-fA-F] ( '\''? [0-9a-fA-F] )*
              | '0' [bB] [01] ( '\''? [01] )*
              ) INTEGER_SUFFIX? ;
fragment INTEGER_SUFFIX : [uU] ( 'l' | 'L' | 'll' | 'LL' )? | ( 'l' | 'L' | 'll' | 'LL' ) [uU]? ;

CHAR_LITERAL   : '\'' ( '\\x' [0-9a-fA-F]+ | '\\' [0-7] [0-7]? [0-7]? | '\\' . | ~['\\] ) '\'' ;
STRING_LITERAL : '"' ( '\\' . | ~["\\] )* '"' ;

WS : [ \t\r\n]+ -> skip ;
LINE_COMMENT : '//' ~[\r\n]* -> skip ;
BLOCK_COMMENT : '/*' .*? '*/' -> skip ;
