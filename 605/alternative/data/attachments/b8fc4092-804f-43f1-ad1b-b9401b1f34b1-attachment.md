## DELETE http://localhost:37397/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Tue, 14 Apr 2026 13:52:02 GMT
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
curl 'http://localhost:37397/echo' \
  -X DELETE
```
