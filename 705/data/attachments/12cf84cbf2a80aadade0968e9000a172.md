## DELETE http://localhost:37111/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Sun, 16 Aug 2026 14:34:28 GMT
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
curl 'http://localhost:37111/echo' \
  -X DELETE
```
