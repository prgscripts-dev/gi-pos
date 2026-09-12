const test = require('node:test');
const assert = require('node:assert/strict');
const {
  getPlatformLoginError,
  normalizeAppPlatform,
  planSupportsPlatform,
  restaurantSupportsPlatform,
} = require('./platform-access');

const plans = {
  Android: { platforms: ['android'] },
  Gold: { platforms: ['windows'] },
  Premium: { platforms: ['windows'] },
  Offline: { platforms: ['windows'] },
};
const getPlan = (name) => plans[name];

test('normalizes supported app platforms and preserves portal login', () => {
  assert.equal(normalizeAppPlatform(' Android '), 'android');
  assert.equal(normalizeAppPlatform('WINDOWS'), 'windows');
  assert.equal(normalizeAppPlatform(''), null);
  assert.throws(() => normalizeAppPlatform('web'), /windows or android/);
});

test('keeps Android and Windows plans strictly separated', () => {
  assert.equal(planSupportsPlatform(plans.Android, 'android'), true);
  assert.equal(planSupportsPlatform(plans.Android, 'windows'), false);
  assert.equal(planSupportsPlatform(plans.Gold, 'windows'), true);
  assert.equal(planSupportsPlatform(plans.Gold, 'android'), false);
});

test('accepts only approved, current restaurants for app login', () => {
  const now = Date.parse('2026-08-14T12:00:00.000Z');
  const restaurant = {
    status: 'approved',
    subscription_status: 'active',
    starts_at: '2026-01-01T00:00:00.000Z',
    expires_at: '2026-12-31T23:59:59.000Z',
    plan_name: 'Android',
  };

  assert.equal(restaurantSupportsPlatform(restaurant, 'android', getPlan, now), true);
  assert.equal(restaurantSupportsPlatform(restaurant, 'windows', getPlan, now), false);
  assert.equal(restaurantSupportsPlatform({ ...restaurant, status: 'pending' }, 'android', getPlan, now), false);
  assert.equal(restaurantSupportsPlatform({ ...restaurant, expires_at: '2026-01-02T00:00:00.000Z' }, 'android', getPlan, now), false);
  assert.equal(restaurantSupportsPlatform(restaurant, null, getPlan, now), true);
});

test('returns clear platform-specific login messages', () => {
  assert.match(getPlatformLoginError('android'), /Android plan/);
  assert.match(getPlatformLoginError('windows'), /Windows POS plan/);
});
