import json
import queue
import socket
import threading
import tkinter as tk
from tkinter import ttk, messagebox


class TcpGuiServer:
    def __init__(self, root):
        self.root = root
        self.root.title("PositionMe TCP Monitor")
        self.root.geometry("900x650")

        self.server_socket = None
        self.client_socket = None
        self.server_thread = None
        self.running = False
        self.data_queue = queue.Queue()

        self.packet_count = 0

        self._build_ui()
        self._poll_queue()

    def _build_ui(self):
        top = ttk.Frame(self.root, padding=10)
        top.pack(fill="x")

        ttk.Label(top, text="Listen IP:").grid(row=0, column=0, sticky="w", padx=5, pady=5)
        self.ip_var = tk.StringVar(value="0.0.0.0")
        self.ip_entry = ttk.Entry(top, textvariable=self.ip_var, width=18)
        self.ip_entry.grid(row=0, column=1, sticky="w", padx=5, pady=5)

        ttk.Label(top, text="Port:").grid(row=0, column=2, sticky="w", padx=5, pady=5)
        self.port_var = tk.StringVar(value="6000")
        self.port_entry = ttk.Entry(top, textvariable=self.port_var, width=8)
        self.port_entry.grid(row=0, column=3, sticky="w", padx=5, pady=5)

        self.start_btn = ttk.Button(top, text="Start Server", command=self.start_server)
        self.start_btn.grid(row=0, column=4, padx=10, pady=5)

        self.stop_btn = ttk.Button(top, text="Stop Server", command=self.stop_server)
        self.stop_btn.grid(row=0, column=5, padx=5, pady=5)

        self.status_var = tk.StringVar(value="Status: stopped")
        ttk.Label(top, textvariable=self.status_var).grid(
            row=0, column=6, sticky="w", padx=10, pady=5
        )

        content = ttk.Frame(self.root, padding=10)
        content.pack(fill="both", expand=True)

        left = ttk.Frame(content)
        left.pack(side="left", fill="both", expand=True, padx=(0, 5))

        right = ttk.Frame(content)
        right.pack(side="right", fill="both", expand=True, padx=(5, 0))

        # Live log
        ttk.Label(left, text="Live JSON Log").pack(anchor="w")
        self.log_text = tk.Text(left, height=30, wrap="word")
        self.log_text.pack(fill="both", expand=True)

        # Parsed data
        ttk.Label(right, text="Latest Parsed Data").pack(anchor="w")

        form = ttk.Frame(right)
        form.pack(fill="x", pady=10)

        self.fields = {}
        rows = [
            "timestamp",
            "packet_count",
            "pdr_x",
            "pdr_y",
            "accel_x",
            "accel_y",
            "accel_z",
            "gyro_x",
            "gyro_y",
            "gyro_z",
            "wifi_lat",
            "wifi_lng",
            "wifi_x",
            "wifi_y",
            "wifi_floor",
            "wifi_count",
            "strongest_ap",
            "strongest_rssi",
        ]

        for i, key in enumerate(rows):
            ttk.Label(form, text=f"{key}:").grid(row=i, column=0, sticky="w", padx=5, pady=3)
            var = tk.StringVar(value="-")
            ttk.Label(form, textvariable=var, width=30).grid(
                row=i, column=1, sticky="w", padx=5, pady=3
            )
            self.fields[key] = var

    def start_server(self):
        if self.running:
            messagebox.showinfo("Info", "Server is already running.")
            return

        host = self.ip_var.get().strip()
        port_text = self.port_var.get().strip()

        if not host:
            messagebox.showerror("Error", "Please enter a listen IP.")
            return

        try:
            port = int(port_text)
        except ValueError:
            messagebox.showerror("Error", "Port must be an integer.")
            return

        self.running = True
        self.server_thread = threading.Thread(
            target=self._server_loop,
            args=(host, port),
            daemon=True
        )
        self.server_thread.start()
        self.status_var.set(f"Status: listening on {host}:{port}")

    def stop_server(self):
        self.running = False

        try:
            if self.client_socket:
                self.client_socket.close()
        except Exception:
            pass
        self.client_socket = None

        try:
            if self.server_socket:
                self.server_socket.close()
        except Exception:
            pass
        self.server_socket = None

        self.status_var.set("Status: stopped")

    def _server_loop(self, host, port):
        try:
            self.server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self.server_socket.bind((host, port))
            self.server_socket.listen(1)

            while self.running:
                self.status_var.set(f"Status: waiting for client on {host}:{port}")
                client_socket, addr = self.server_socket.accept()
                self.client_socket = client_socket
                self.status_var.set(f"Status: connected to {addr[0]}:{addr[1]}")

                with client_socket:
                    buffer = ""
                    while self.running:
                        data = client_socket.recv(4096)
                        if not data:
                            break

                        buffer += data.decode("utf-8", errors="replace")

                        while "\n" in buffer:
                            line, buffer = buffer.split("\n", 1)
                            line = line.strip()
                            if not line:
                                continue

                            self.data_queue.put(line)

                self.client_socket = None
                if self.running:
                    self.status_var.set("Status: client disconnected, waiting again...")

        except OSError as e:
            if self.running:
                self.data_queue.put(f"__ERROR__:{e}")
        finally:
            self.stop_server()

    def _poll_queue(self):
        try:
            while True:
                item = self.data_queue.get_nowait()

                if item.startswith("__ERROR__:"):
                    self.status_var.set(f"Status: error - {item[len('__ERROR__:'):]}")
                    continue

                self.log_text.insert("end", item + "\n")
                self.log_text.see("end")
                self._parse_and_display(item)
        except queue.Empty:
            pass

        self.root.after(100, self._poll_queue)

    def _parse_and_display(self, raw_line):
        try:
            packet = json.loads(raw_line)
        except json.JSONDecodeError:
            return

        self.packet_count += 1
        self.fields["packet_count"].set(str(self.packet_count))
        self.fields["timestamp"].set(str(packet.get("timestamp", "-")))

        # PDR
        pdr = packet.get("pdr", {})
        self.fields["pdr_x"].set(self._fmt(pdr.get("x")))
        self.fields["pdr_y"].set(self._fmt(pdr.get("y")))

        # IMU
        imu = packet.get("imu", {})
        self.fields["accel_x"].set(self._fmt(imu.get("accel_x")))
        self.fields["accel_y"].set(self._fmt(imu.get("accel_y")))
        self.fields["accel_z"].set(self._fmt(imu.get("accel_z")))
        self.fields["gyro_x"].set(self._fmt(imu.get("gyro_x")))
        self.fields["gyro_y"].set(self._fmt(imu.get("gyro_y")))
        self.fields["gyro_z"].set(self._fmt(imu.get("gyro_z")))

        # Wi-Fi
        wifi = packet.get("wifi", {})
        self.fields["wifi_lat"].set(self._fmt(wifi.get("lat"), digits=6))
        self.fields["wifi_lng"].set(self._fmt(wifi.get("lng"), digits=6))
        self.fields["wifi_x"].set(self._fmt(wifi.get("x")))
        self.fields["wifi_y"].set(self._fmt(wifi.get("y")))
        self.fields["wifi_floor"].set(str(wifi.get("floor", "-")))

        access_points = wifi.get("access_points", [])
        self.fields["wifi_count"].set(str(len(access_points)))

        strongest_ap = "-"
        strongest_rssi = "-"
        if access_points:
            best = max(access_points, key=lambda ap: ap.get("rssi", -9999))
            strongest_ap = best.get("bssid", "-")
            strongest_rssi = str(best.get("rssi", "-"))

        self.fields["strongest_ap"].set(strongest_ap)
        self.fields["strongest_rssi"].set(strongest_rssi)

    @staticmethod
    def _fmt(value, digits=3):
        if value is None:
            return "-"
        try:
            return f"{float(value):.{digits}f}"
        except (TypeError, ValueError):
            return str(value)


if __name__ == "__main__":
    root = tk.Tk()
    app = TcpGuiServer(root)
    root.protocol("WM_DELETE_WINDOW", lambda: (app.stop_server(), root.destroy()))
    root.mainloop()