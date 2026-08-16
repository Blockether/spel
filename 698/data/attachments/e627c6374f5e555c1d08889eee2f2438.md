## DELETE http://localhost:37545/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Sun, 16 Aug 2026 02:40:12 GMT
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
curl 'http://localhost:37545/echo' \
  -X DELETE
```
