## DELETE http://localhost:33251/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Sat, 05 Sep 2026 06:13:37 GMT
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
curl 'http://localhost:33251/echo' \
  -X DELETE
```
