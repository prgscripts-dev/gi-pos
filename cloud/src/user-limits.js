function parseUserLimit(value) {
  if (value == null || value === '' || String(value).toLowerCase() === 'unlimited') return null;
  const limit = Number(value);
  if (!Number.isInteger(limit) || limit < 1 || limit > 10000) {
    const error = new Error('User limit must be Unlimited or a whole number between 1 and 10000');
    error.statusCode = 400;
    throw error;
  }
  return limit;
}

function enforceUserLimit(subscription, directory) {
  const maxUsers = subscription?.max_users == null ? null : Number(subscription.max_users);
  if (maxUsers == null) return;
  let parsedDirectory = directory;
  if (typeof parsedDirectory === 'string') {
    try {
      parsedDirectory = JSON.parse(parsedDirectory);
    } catch {
      parsedDirectory = [];
    }
  }
  const users = Array.isArray(parsedDirectory) ? parsedDirectory : [];
  const activeUsers = Math.max(1, users.filter((user) => user?.active !== false).length);
  if (activeUsers > maxUsers) {
    const error = new Error(`User limit reached. This account allows ${maxUsers} active user${maxUsers === 1 ? '' : 's'}, but ${activeUsers} were submitted.`);
    error.statusCode = 409;
    throw error;
  }
}

module.exports = { enforceUserLimit, parseUserLimit };
