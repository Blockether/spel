## DELETE http://localhost:45429/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Sun, 22 Nov 2026 12:25:22 GMT
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
curl 'http://localhost:45429/echo' \
  -X DELETE
```
