const { Pool } = require('pg');

function createPool() {
  const connectionString = process.env.DATABASE_URL;

  if (!connectionString) {
    throw new Error('DATABASE_URL is required');
  }

  return new Pool({
    connectionString,
    max: Number(process.env.PG_POOL_SIZE || 10),
  });
}

module.exports = {
  createPool,
};
