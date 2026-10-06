import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { execFileSync } from 'node:child_process'

// Deliberately fixed to the isolated integration environment. Never targets the main database.
const base = 'http://localhost:8081'
const database = 'quiktech_admin_checks'
const username = process.env.ADMIN_USERNAME
const password = process.env.ADMIN_PASSWORD
assert.ok(username && password, 'Set ADMIN_USERNAME and ADMIN_PASSWORD in the process environment')
const suffix = randomUUID().replaceAll('-', '')
const constraint = `audit_check_${suffix}`
const reason = `rollback-${suffix}`

function sql(statement) {
  execFileSync('docker', ['exec', '-i', 'quiktech-pos-db', 'psql', '-U', 'quiktech-pos', '-d', database, '-v', 'ON_ERROR_STOP=1'], { input: statement, stdio: ['pipe', 'pipe', 'pipe'] })
}

async function request(path, token, body, method = body ? 'POST' : 'GET', expected = 200) {
  const response = await fetch(base + path, { method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) })
  const result = await response.json()
  assert.equal(response.status, expected, `${method} ${path}: ${result?.error?.code || response.status}`)
  return result.data
}

const admin = await request('/api/auth/login', null, { usernameOrEmail: username, password })
const token = admin.accessToken
const owner = await request('/api/auth/register', null, { username: `rollback-${suffix.slice(0, 8)}`, email: `${suffix}@example.invalid`, fullName: 'Audit rollback check', password: randomUUID() })
try {
  const created = await request('/api/businesses/default', owner.accessToken, {}, 'POST', 201)
  const businessId = created.business.id
  const before = await request(`/api/admin/subscriptions/${businessId}`, token)
  sql(`ALTER TABLE audit_logs ADD CONSTRAINT ${constraint} CHECK ((new_value->>'reason') IS DISTINCT FROM '${reason}');`)
  try {
    await request(`/api/admin/subscriptions/${businessId}/plan`, token, { plan: 'PRO', billingCycle: 'YEARLY', reason }, 'PATCH', 500)
    const after = await request(`/api/admin/subscriptions/${businessId}`, token)
    assert.deepEqual(after, before, 'Audit insert failure must roll back the subscription mutation')
    const logs = await request(`/api/admin/audit-logs?businessId=${businessId}`, token)
    assert.equal(logs.content.length, 0, 'Failed transaction must not leave a success audit')
    console.log('PASS: real PostgreSQL audit insert failure rolls back the plan and leaves no success log')
  } finally {
    sql(`ALTER TABLE audit_logs DROP CONSTRAINT IF EXISTS ${constraint};`)
  }
} finally {
  await request(`/api/admin/users/${owner.user.id}`, token, { reason: 'Rollback test cleanup' }, 'DELETE')
}
