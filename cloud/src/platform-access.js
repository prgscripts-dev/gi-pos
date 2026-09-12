const APP_PLATFORMS = new Set(['windows', 'android']);

function normalizeAppPlatform(value) {
  const platform = String(value || '').trim().toLowerCase();
  if (!platform) return null;
  if (!APP_PLATFORMS.has(platform)) {
    const error = new Error('App platform must be windows or android');
    error.statusCode = 400;
    throw error;
  }
  return platform;
}

function planSupportsPlatform(plan, platform) {
  if (!platform) return true;
  return Array.isArray(plan?.platforms) && plan.platforms.includes(platform);
}

function restaurantSupportsPlatform(restaurant, platform, getPlanDefinition, now = Date.now()) {
  if (!platform) return true;
  if (!restaurant || restaurant.status !== 'approved') return false;
  if (!['active', 'trial'].includes(String(restaurant.subscription_status || '').toLowerCase())) return false;

  const startsAt = Date.parse(String(restaurant.starts_at || ''));
  const expiresAt = Date.parse(String(restaurant.expires_at || ''));
  if (!Number.isFinite(startsAt) || !Number.isFinite(expiresAt) || startsAt > now || expiresAt < now) return false;

  return planSupportsPlatform(getPlanDefinition(restaurant.plan_name, restaurant.max_devices), platform);
}

function getPlatformLoginError(platform) {
  return platform === 'android'
    ? 'This account does not have an active Android plan. Use an Android-plan restaurant or change the plan in GI Cloud Admin.'
    : 'This account does not have an active Windows POS plan. Android-only accounts must use GI POS Mobile or change the plan in GI Cloud Admin.';
}

module.exports = {
  getPlatformLoginError,
  normalizeAppPlatform,
  planSupportsPlatform,
  restaurantSupportsPlatform,
};
