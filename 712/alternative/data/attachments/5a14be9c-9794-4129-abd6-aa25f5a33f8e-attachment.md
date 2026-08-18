## DELETE http://localhost:44345/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Mon, 17 Aug 2026 23:29:58 GMT
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
curl 'http://localhost:44345/echo' \
  -X DELETE
```
