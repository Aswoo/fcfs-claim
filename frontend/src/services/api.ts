import axios from 'axios';

export const BASE_URL = 'http://192.168.0.8:8081';

export const api = axios.create({
  baseURL: BASE_URL,
  timeout: 5000,
  headers: { 'Content-Type': 'application/json' },
});
