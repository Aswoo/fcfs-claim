import axios from 'axios';

export const BASE_URL = 'http://172.28.95.131:8081';

export const api = axios.create({
  baseURL: BASE_URL,
  timeout: 5000,
  headers: { 'Content-Type': 'application/json' },
});
