/**
 * Minimal JUnit XML reporter for Node.js built-in test runner.
 * Usage: node --test --test-reporter=./scripts/junit-reporter.mjs --test-reporter-destination=test-results.xml
 */

import { Transform } from 'node:stream'

const cases = []
let startTime = Date.now()

export default class JUnitReporter extends Transform {
  constructor() {
    super({ readableObjectMode: false, writableObjectMode: true })
    startTime = Date.now()
  }

  _transform(event, _encoding, callback) {
    if (event.type === 'test:pass' || event.type === 'test:fail') {
      const duration = ((event.data.details?.duration_ms ?? 0) / 1000).toFixed(3)
      const name = escape(event.data.name ?? 'unknown')
      if (event.type === 'test:fail') {
        const msg = escape(String(event.data.details?.error?.message ?? 'failed'))
        cases.push(`    <testcase name="${name}" time="${duration}"><failure message="${msg}"/></testcase>`)
      } else {
        cases.push(`    <testcase name="${name}" time="${duration}"/>`)
      }
    }
    callback()
  }

  _flush(callback) {
    const elapsed = ((Date.now() - startTime) / 1000).toFixed(3)
    const total = cases.length
    const failures = cases.filter(c => c.includes('<failure')).length
    const xml = [
      '<?xml version="1.0" encoding="UTF-8"?>',
      `<testsuite name="node:test" tests="${total}" failures="${failures}" time="${elapsed}">`,
      ...cases,
      '</testsuite>',
    ].join('\n')
    this.push(xml + '\n')
    callback()
  }
}

function escape(str) {
  return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}
