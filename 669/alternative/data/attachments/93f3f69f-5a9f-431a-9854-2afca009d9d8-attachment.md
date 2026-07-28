## DELETE http://localhost:37395/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Tue, 28 Jul 2026 15:07:29 GMT
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
curl 'http://localhost:37395/echo' \
  -X DELETE
```
