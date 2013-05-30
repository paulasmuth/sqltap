// This file is part of the "SQLTap" project
//   (c) 2011-2013 Paul Asmuth <paul@paulasmuth.com>
//
// Licensed under the MIT License (the "License"); you may not use this
// file except in compliance with the License. You may obtain a copy of
// the License at: http://opensource.org/licenses/MIT

package com.paulasmuth.sqltap

import scala.collection.mutable.ListBuffer

// FIXPAUL this is shit... rewrite to a proper state machine w/o regex
object QueryParser {

  //var scope = 'root
  //var depth = 0

  /*val t_rsrc = """^([0-9a-z_\-]+)\.(.*)""".r // fixpaul
  val t_sfld = """^([0-9a-z_\-]+)([,\}].*)""".r // fixpaul
  val t_sarg = """^([^,\)]+)(.*)""".r
  val t_func = """^(findOne|findAllWhere|findAll|countAll)\.?(.*)""".r
  val t_rbrs = """^\((.*)""".r
  val t_rbre = """^\)(.*)""".r
  val t_cbrs = """^\{(.*)""".r
  val t_cbre = """^\}(.*)""".r
  val t_ssep = """^,(.*)""".r
  val t_fall = """\*([\},].*)""".r*/

  private val PARSER_STATE_NEXT   = 1
  private val PARSER_STATE_CMD    = 2
  private val PARSER_STATE_ARG    = 3
  private val PARSER_STATE_ARGSTR = 4
  private val PARSER_STATE_BODY   = 5

  def parse(query: Query) : Unit = {
    val stack = new InstructionStack()
    var args  = new ListBuffer[String]()
    var state = PARSER_STATE_NEXT
    val qry   = query.query_string

    if (qry == null)
      throw new ParseException("no query string")

    stack.push_down(query)

    var cur = 0
    var len = qry.length
    var pos = 0

    while (cur < len) {
      qry.charAt(cur) match {

        case '.' => state match {

          case PARSER_STATE_NEXT => {
            args += qry.substring(pos, cur)
            state = PARSER_STATE_CMD
            pos = cur + 1
          }

        }

        case '(' => state match {

          case PARSER_STATE_CMD => {
            args += qry.substring(pos, cur)
            state = PARSER_STATE_ARG
            pos = cur + 1
          }

        }

        case ',' => state match {

          case PARSER_STATE_ARG => {
            args += qry.substring(pos, cur)
            state = PARSER_STATE_ARG
            pos = cur + 1
          }

          case PARSER_STATE_NEXT => {
            if (cur != pos)
              stack.push_field(qry.substring(pos, cur))

            state = PARSER_STATE_NEXT
            pos = cur + 1
          }

        }

        case ')' => state match {

          case PARSER_STATE_ARG => {
            args += qry.substring(pos, cur)
            state = PARSER_STATE_BODY
            pos = cur + 1
          }

        }

        case '"' => state match {

          case PARSER_STATE_ARG => {
            state = PARSER_STATE_ARGSTR
          }

          case PARSER_STATE_ARGSTR => {
            state = PARSER_STATE_ARG
          }

        }

        case '{' => state match {

          case PARSER_STATE_BODY => {
            state = PARSER_STATE_NEXT

            stack.push_down(
              InstructionFactory.make(clean_args(args)))

            args.clear
            pos = cur + 1
          }

          case PARSER_STATE_CMD => {
            state = PARSER_STATE_NEXT
            args += qry.substring(pos, cur)

            stack.push_down(
              InstructionFactory.make(clean_args(args)))

            args.clear
            pos = cur + 1
          }

        }

        case '}' => state match {

          case PARSER_STATE_NEXT => {
            state = PARSER_STATE_NEXT

            if (cur != pos)
              stack.push_field(qry.substring(pos, cur))

            stack.pop()
            pos = cur + 1
          }

        }


        case _ => ()

      }

      cur += 1
    }
  }

  private def clean_args(args: ListBuffer[String]) : ListBuffer[String] = {
    for (ind <- (0 until args.length)) {

      if (args(ind).charAt(args(ind).length - 1) == '"')
        args(ind) = args(ind).substring(0, args(ind).length - 1)

      if (args(ind).charAt(0) == '"')
        args(ind) = args(ind).substring(1)

    }

    args
  }

}
