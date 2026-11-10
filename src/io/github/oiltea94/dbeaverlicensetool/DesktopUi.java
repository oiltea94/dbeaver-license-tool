package io.github.oiltea94.dbeaverlicensetool;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.WindowConstants;

public final class DesktopUi {
    private final List<Runnable> translations = new ArrayList<>();
    private final JComboBox<I18n.Language> language = new JComboBox<>(I18n.Language.values());
    private java.util.function.Supplier<String> statusText = () -> I18n.t("选择安装目录后扫描，检测结果将显示在这里。");
    private final JFrame frame = new JFrame(I18n.t("DBeaver 本地授权工具"));
    private final JTextField install = new JTextField();
    private final JComboBox<Target> targets = new JComboBox<>();
    private final JLabel status = new JLabel(statusText.get());
    private final JTextField name = new JTextField();
    private final JTextField email = new JTextField();
    private final JTextField company = new JTextField();
    private final JTextField expires = new JTextField(LocalDate.now().plusYears(1).toString());
    private final JTextField users = new JTextField("1");
    private final JTextArea log = new JTextArea(10, 64);
    private final JButton browse = button("选择目录…");
    private final JButton scan = button("扫描版本");
    private final JButton detect = button("检查兼容性");
    private final JButton activate = button("生成并安装");
    private final JButton restore = button("从备份还原");
    private final JButton open = button("打开输出目录");
    private final JButton launch = button("启动 DBeaver");
    private final JCheckBox autoLaunch = new JCheckBox();
    @FunctionalInterface interface LaunchAction { ClientLauncher.Result launch(Target target) throws Exception; }
    private final LaunchAction launchAction;
    private Target launchTarget;
    private final List<Component> controls = new ArrayList<>();
    private boolean busy;
    private boolean updatingTargets;
    private Target inspected;
    private boolean supported;
    private Path output;

    private DesktopUi() {
        this(Engine::launch);
    }

    DesktopUi(LaunchAction launchAction) {
        this.launchAction = launchAction;
        JPanel content = new JPanel(new BorderLayout(0, 18));
        content.setBorder(BorderFactory.createEmptyBorder(24, 28, 24, 28));
        JPanel title = new JPanel();
        title.setLayout(new BoxLayout(title, BoxLayout.Y_AXIS));
        JLabel heading = label("DBeaver 本地授权工具");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 25f));
        title.add(heading);
        title.add(Box.createVerticalStrut(8));
        title.add(label("选择安装版本，填写授权资料；操作前请关闭 DBeaver。"));
        JPanel header = new JPanel(new BorderLayout(16, 0));
        header.add(title, BorderLayout.CENTER);
        JPanel languages = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
        languages.add(label("语言"));
        language.setSelectedItem(I18n.language());
        language.setName("language");
        languages.add(language);
        header.add(languages, BorderLayout.EAST);
        content.add(header, BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(0, 16));
        JPanel form = new JPanel(new GridBagLayout());
        JPanel folder = new JPanel(new BorderLayout(8, 0));
        folder.add(install, BorderLayout.CENTER);
        JPanel folderButtons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0));
        folderButtons.add(browse); folderButtons.add(scan);
        folder.add(folderButtons, BorderLayout.EAST);
        row(form, 0, "安装目录", folder);
        targets.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean selected, boolean focus) {
                return super.getListCellRendererComponent(list,
                        value instanceof Target t ? t.productId() + "  ·  " + t.version() + "  ·  " + t.home() : I18n.t("请选择版本"),
                        index, selected, focus);
            }
        });
        row(form, 1, "产品 / 版本", targets);
        row(form, 2, "检测结果", status);
        row(form, 3, "姓名", name);
        row(form, 4, "邮箱", email);
        row(form, 5, "单位", company);
        row(form, 6, "到期日期", expires);
        row(form, 7, "席位数量", users);
        translations.add(() -> expires.setToolTipText(I18n.t("格式：YYYY-MM-DD，日期应晚于今天")));
        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
        actions.add(detect); actions.add(activate); actions.add(launch); actions.add(restore); actions.add(open);
        row(form, 8, "", actions);
        autoLaunch.setSelected(false);
        translations.add(() -> autoLaunch.setText(I18n.t("完成后启动 DBeaver")));
        row(form, 9, "", autoLaunch);
        body.add(form, BorderLayout.NORTH);
        log.setEditable(false);
        log.setLineWrap(true); log.setWrapStyleWord(true);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        log.setMargin(new Insets(10, 10, 10, 10));
        JScrollPane scroll = new JScrollPane(log);
        translations.add(() -> scroll.setBorder(BorderFactory.createTitledBorder(I18n.t("操作日志"))));
        body.add(scroll, BorderLayout.CENTER);
        content.add(body, BorderLayout.CENTER);
        frame.setContentPane(content);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent event) {
                if (busy) {
                    message(I18n.t("操作仍在进行，请等待完成后关闭窗口。"), I18n.t("正在处理"), JOptionPane.INFORMATION_MESSAGE);
                } else frame.dispose();
            }
        });
        controls.addAll(List.of(language, install, targets, name, email, company, expires, users, browse, scan, detect, activate, restore, open, launch, autoLaunch));
        install.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { resetSelection(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { resetSelection(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { resetSelection(); }
        });
        browse.addActionListener(e -> chooseFolder());
        scan.addActionListener(e -> scanFolder());
        install.addActionListener(e -> scanFolder());
        targets.addActionListener(e -> { if (!updatingTargets && !busy) inspectSelected(); });
        detect.addActionListener(e -> inspectSelected());
        activate.addActionListener(e -> activateSelected());
        restore.addActionListener(e -> restoreSelected());
        open.addActionListener(e -> openOutput());
        launch.addActionListener(e -> launchSelected());
        language.addActionListener(e -> {
            try { I18n.select((I18n.Language) language.getSelectedItem(), true); }
            catch (Exception ex) { append(I18n.t("语言偏好保存失败，下次启动将使用此前的设置。") + " " + ex.getMessage()); }
            applyLanguage();
        });
        frame.setMinimumSize(new Dimension(1050, 690));
        frame.setSize(1160, 760);
        applyLanguage();
        frame.setLocationRelativeTo(null);
        refreshControls();
    }

    private JLabel label(String key) {
        JLabel component = new JLabel(I18n.t(key));
        translations.add(() -> component.setText(I18n.t(key)));
        return component;
    }

    private JButton button(String key) {
        JButton component = new JButton(I18n.t(key));
        translations.add(() -> component.setText(I18n.t(key)));
        return component;
    }

    private void setStatus(java.util.function.Supplier<String> text) {
        statusText = text;
        status.setText(text.get());
    }

    private void applyLanguage() {
        frame.setTitle(I18n.t("DBeaver 本地授权工具"));
        frame.setLocale(I18n.locale());
        translations.forEach(Runnable::run);
        status.setText(statusText.get());
        if (status.getToolTipText() != null) status.setToolTipText(statusText.get());
        targets.repaint();
        frame.revalidate();
        frame.repaint();
    }

    private void message(String text, String title, int type) {
        JOptionPane.showOptionDialog(frame, text, title, JOptionPane.DEFAULT_OPTION, type,
                null, new Object[]{I18n.t("确定")}, I18n.t("确定"));
    }

    public static void show() {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) { /* Swing's default remains available. */ }
        DesktopUi ui = new DesktopUi();
        ui.frame.setVisible(true);
        ui.task(I18n.t("读取常用安装路径"), Engine::defaultLocations, locations -> {
            if (!locations.isEmpty()) ui.install.setText(locations.getFirst().toString());
            ui.append(I18n.t("可选择 Windows / Linux 安装目录或 macOS .app。仅对检测到的结构报告兼容性。"));
        });
    }

    private void row(JPanel panel, int index, String label, Component input) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = index; c.gridx = 0; c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(5, 0, 5, 14);
        panel.add(label(label), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(5, 0, 5, 0);
        panel.add(input, c);
    }

    private void chooseFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setLocale(I18n.locale());
        chooser.updateUI();
        chooser.setApproveButtonText(I18n.t("打开"));
        chooser.setDialogTitle(I18n.t("选择 DBeaver 安装目录或 .app"));
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.putClientProperty("JFileChooser.packageIsTraversable", "always");
        chooser.putClientProperty("JFileChooser.appBundleIsTraversable", "always");
        if (!install.getText().isBlank()) {
            try { chooser.setCurrentDirectory(Path.of(install.getText().trim()).toFile()); }
            catch (Exception ignored) { /* User can select a valid path in the chooser. */ }
        }
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            install.setText(chooser.getSelectedFile().toPath().toString());
            scanFolder();
        }
    }

    private void scanFolder() {
        final Path path;
        try {
            if (install.getText().isBlank()) throw new IllegalArgumentException(I18n.t("请先选择安装目录。"));
            path = Path.of(install.getText().trim());
        } catch (Exception ex) { showError(ex); return; }
        inspected = null; supported = false;
        setStatus(() -> I18n.t("正在扫描…"));
        task(I18n.t("扫描安装版本"), () -> Engine.scan(path), found -> {
            updatingTargets = true;
            try {
                targets.removeAllItems();
                for (var target : found) targets.addItem(target);
            } finally { updatingTargets = false; }
            setStatus(() -> found.isEmpty() ? I18n.t("未找到可识别的 DBeaver 安装。") : I18n.t("找到 ") + found.size() + I18n.t(" 个安装，正在检测所选版本…"));
            append(I18n.t("扫描结果：") + found.size() + I18n.t(" 个安装。"));
            if (!found.isEmpty()) SwingUtilities.invokeLater(this::inspectSelected);
        });
    }

    private Target selected() { return (Target) targets.getSelectedItem(); }

    private void resetSelection() {
        launchTarget = null;
        inspected = null; supported = false;
        updatingTargets = true;
        try { targets.removeAllItems(); }
        finally { updatingTargets = false; }
        setStatus(() -> I18n.t("路径已修改，请扫描安装版本。"));
        status.setForeground(UIManager.getColor("Label.foreground"));
        status.setToolTipText(null);
        refreshControls();
    }

    private void inspectSelected() {
        if (busy || selected() == null) return;
        Target target = selected();
        inspected = null; supported = false;
        setStatus(() -> I18n.t("正在检查结构兼容性…"));
        task(I18n.t("检查 ") + target.productId() + " " + target.version(), () -> Engine.inspect(target), result -> {
            inspected = target; supported = result.supported();
            launchTarget = result.supported() && Engine.hasLocalLicense(target) ? target : null;
            setStatus(() -> result.supported() ? I18n.t("结构兼容，详细检测结果见日志。") : I18n.t("结构不兼容，详细原因见日志。"));
            status.setForeground(result.supported() ? new Color(25, 110, 60) : new Color(170, 65, 40));
            status.setToolTipText(statusText.get());
            append(result.detail());
        });
    }

    private void activateSelected() {
        Target target = selected();
        if (target == null || !target.equals(inspected) || !supported) return;
        final Profile profile;
        try { profile = validateProfile(name.getText(), email.getText(), company.getText(), expires.getText(), users.getText()); }
        catch (IllegalArgumentException ex) { showError(ex); return; }
        task(I18n.t("生成并安装"), () -> Engine.activate(target, profile, this::append), result -> installationFinished(target, result));
    }

    private void installationFinished(Target target, Engine.Result result) {
            output = result.license().getParent();
            launchTarget = target;
            append(result.summary());
            append(I18n.t("授权文件：") + result.license());
            append(I18n.t("备份目录：") + result.backup());
            if(result.registration()!=null)append(I18n.t("注册信息保存位置：") + result.registration());
            setStatus(() -> I18n.t("注册信息已导入并验证，可从原快捷方式正常启动 DBeaver。"));
            if (autoLaunch.isSelected()) SwingUtilities.invokeLater(() -> {
                if (target.equals(selected())) launchSelected();
            });
    }

    private void launchSelected() {
        Target target = selected();
        if (target == null || !target.equals(launchTarget)) return;
        task(I18n.t("启动 DBeaver"), () -> launchAction.launch(target), result -> {
            append(I18n.t("已提交启动请求，进程 ID：") + result.pid());
            append(I18n.t("启动日志：") + result.log());
            setStatus(() -> I18n.t("已提交启动请求；客户端状态请在 DBeaver 中查看。"));
        });
    }

    private void restoreSelected() {
        Target target = selected();
        if (target == null) return;
        task(I18n.t("从备份还原"), () -> Engine.restore(target, this::append), result -> {
            launchTarget = null;
            append(result); inspected = null; supported = false;
            setStatus(() -> I18n.t("还原操作完成；正在重新检查结构…"));
            SwingUtilities.invokeLater(this::inspectSelected);
        });
    }

    private void openOutput() {
        Path folder = output;
        if (folder == null) return;
        task(I18n.t("打开输出目录"), () -> {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN))
                throw new IllegalStateException(I18n.t("当前桌面环境没有文件夹打开服务。目录：") + folder);
            Desktop.getDesktop().open(folder.toFile());
            return folder;
        }, opened -> append(I18n.t("输出目录：") + opened));
    }

    private <T> void task(String label, Callable<T> work, Consumer<T> complete) {
        if (busy) return;
        busy = true; refreshControls(); append(label + "…");
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return work.call(); }
            @Override protected void done() {
                try { complete.accept(get()); }
                catch (Exception ex) { setStatus(() -> I18n.t("操作失败，详细原因见日志。")); showError(ex); }
                finally { busy = false; refreshControls(); }
            }
        }.execute();
    }

    private void refreshControls() {
        for (Component control : controls) control.setEnabled(!busy);
        detect.setEnabled(!busy && selected() != null);
        restore.setEnabled(!busy && selected() != null);
        activate.setEnabled(!busy && supported && selected() != null && selected().equals(inspected));
        open.setEnabled(!busy && output != null);
        launch.setEnabled(!busy && selected() != null && selected().equals(launchTarget));
        frame.setCursor(java.awt.Cursor.getPredefinedCursor(busy ? java.awt.Cursor.WAIT_CURSOR : java.awt.Cursor.DEFAULT_CURSOR));
    }

    private void append(String message) {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(() -> append(message)); return; }
        log.append(message + System.lineSeparator());
        log.setCaretPosition(log.getDocument().getLength());
    }

    private void showError(Throwable ex) {
        String message = Main.errorMessage(ex);
        append(I18n.t("错误：") + message);
        message(message, I18n.t("操作未完成"), JOptionPane.ERROR_MESSAGE);
    }

    static Profile validateProfile(String name, String email, String company, String expires, String users) {
        name = name.trim(); email = email.trim(); company = company.trim();
        if (name.isEmpty()) throw new IllegalArgumentException(I18n.t("姓名不能为空。"));
        if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new IllegalArgumentException(I18n.t("请输入有效邮箱地址。"));
        if (company.isEmpty()) throw new IllegalArgumentException(I18n.t("单位不能为空。"));
        LocalDate date;
        try { date = LocalDate.parse(expires.trim()); }
        catch (DateTimeParseException ex) { throw new IllegalArgumentException(I18n.t("到期日期须为有效的 YYYY-MM-DD 日期。"), ex); }
        if (!date.isAfter(LocalDate.now())) throw new IllegalArgumentException(I18n.t("到期日期应晚于今天。"));
        int seats;
        try { seats = Integer.parseInt(users.trim()); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(I18n.t("席位数量须为正整数。"), ex); }
        if (seats < 1) throw new IllegalArgumentException(I18n.t("席位数量须大于零。"));
        Profile profile = new Profile(name, email, company, date, seats);
        LicenseApi.validate(profile);
        return profile;
    }
}
