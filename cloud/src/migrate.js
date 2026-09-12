const fs = require('node:fs');
const path = require('node:path');
const { createPool } = require('./db');

async function main() {
  const pool = createPool();
  const schema = fs.readFileSync(path.join(__dirname, '..', 'schema.sql'), 'utf8');

  try {
    await pool.query(schema);
    console.log('Cloud database migration complete');
  } finally {
    await pool.end();
  }
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
