import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { LinearGradient } from 'expo-linear-gradient';
import { Colors } from '../../constants/colors';

export const Banner = React.memo(() => {
  return (
    <LinearGradient
      colors={[Colors.brandGreenDark, '#2C5234']}
      start={{ x: 0, y: 0 }}
      end={{ x: 1, y: 1 }}
      style={styles.container}
    >
      <Text style={styles.label}>LIMITED EDITION</Text>
      <Text style={styles.title}>2025 스타벅스{'\n'}프리퀀시 리워드</Text>
      <Text style={styles.desc}>선착순 한정 수량 · 지금 바로 신청하세요</Text>
    </LinearGradient>
  );
});

const styles = StyleSheet.create({
  container: {
    height: 96,
    padding: 16,
    flexDirection: 'column',
    justifyContent: 'center',
    gap: 4,
  },
  label: {
    fontSize: 9,
    fontWeight: '700',
    letterSpacing: 2,
    color: Colors.goldLight,
  },
  title: {
    fontSize: 20,
    fontWeight: '400',
    color: '#F2F0EB',
    lineHeight: 24,
  },
  desc: {
    fontSize: 10,
    fontWeight: '400',
    color: Colors.headerSub,
  },
});
