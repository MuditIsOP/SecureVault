// Vercel serverless entry point
// Re-exports the Express app from functions/index.js
const app = require('../functions/index');
module.exports = app;
