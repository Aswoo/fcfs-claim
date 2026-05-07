import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Colors } from '../../constants/colors';

export const Header = React.memo(() => {
  const insets = useSafeAreaInsets();
  return (
    <View style={[styles.container, { paddingTop: insets.top + 8 }]}>
      <View style={styles.logo} />
      <View style={styles.titleGroup}>
        <Text style={styles.title}>스타벅스 프리퀀시</Text>
        <Text style={styles.subtitle}>한정 수량 선착순 지급</Text>
      </View>
      <View style={styles.freqChip}>
        <Text style={styles.freqChipText}>FREQ</Text>
      </View>
    </View>
  );
});

const styles = StyleSheet.create({
  container: {
    backgroundColor: Colors.brandGreenDark,
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingBottom: 12,
    gap: 8,
  },
  logo: {
    width: 28,
    height: 28,
    borderRadius: 14,
    backgroundColor: Colors.brandGreenMid,
  },
  titleGroup: {
    flex: 1,
  },
  title: {
    fontSize: 13,
    fontWeight: '700',
    color: Colors.white,
  },
  subtitle: {
    fontSize: 10,
    fontWeight: '400',
    color: Colors.headerSub,
  },
  freqChip: {
    backgroundColor: Colors.gold,
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: 20,
  },
  freqChipText: {
    fontSize: 10,
    fontWeight: '700',
    color: Colors.brandGreenDark,
  },
});
