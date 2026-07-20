## DELETE http://localhost:37825/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Mon, 20 Jul 2026 10:37:10 GMT
```

### Response Body
```json
{
  "method": "DELETE",
  "path": "/echo"
}
```

### cURL
```bash
curl 'http://localhost:37825/echo' \
  -X DELETE
```
